package kr.co.dtonic.datacatalog;

import java.util.*;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.application.*;
import kr.co.dtonic.datacatalog.standard.export.DictionaryExportModels.ExportRequest;
import kr.co.dtonic.datacatalog.standard.export.DictionaryExportService;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipModels.*;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipService;
import kr.co.dtonic.datacatalog.standard.search.StandardLookupService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class StandardLookupPostgresIT {
    @Autowired JdbcTemplate db;@Autowired DictionaryService dictionaries;@Autowired DictionaryRelationshipService relations;
    @Autowired DictionaryExportService exports;@Autowired StandardLookupService lookup;@Autowired DraftFieldsService fields;@Autowired MetadataWorkflowService metadata;@Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){PostgresDatabase.configure(registry);}
    @Test void literalWholeDictionarySearchFiltersBeforePaginationAndMatchesWordAttributes(){
        UUID org=org();var editor=actor(org);UUID dict=dict(org,editor);
        for(int i=0;i<24;i++)dictionaries.createEntry(dict,new EntryInput("WORD","word_"+i,"다른 항목 "+i,"정의","STRING"),editor,"test");
        var target=dictionaries.createEntry(dict,new EntryInput("WORD","word_target","100% 항목","특수_정의","STRING"),editor,"test");
        relations.word(UUID.fromString(target.id()),tag(target),new WordAttributes("UniqueEnglish","UE",List.of()),editor,"test");
        assertThat(dictionaries.entries(dict,"WORD","UniqueEnglish","DRAFT",0,1,editor)).extracting(DictionaryEntry::id).containsExactly(target.id());
        assertThat(dictionaries.entries(dict,"WORD","%",null,0,20,editor)).extracting(DictionaryEntry::id).containsExactly(target.id());
        assertThat(dictionaries.entries(dict,"WORD",null,"APPROVED",0,20,editor)).isEmpty();
        assertThatThrownBy(()->dictionaries.entries(dict,"WORD",null,null,0,20,actor(org()))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void selectedXlsxValidatesEveryIdAndRetainsLiteralCellsAndReplay() throws Exception {
        UUID org=org();var editor=actor(org);UUID dict=dict(org,editor);
        var chosen=dictionaries.createEntry(dict,new EntryInput("WORD","selected","=1+1","정의","STRING"),editor,"test");
        var other=dictionaries.createEntry(dict,new EntryInput("WORD","other","제외","정의","STRING"),editor,"test");
        var input=new ExportRequest("WORD",null,"DRAFT",List.of(UUID.fromString(chosen.id())));
        var result=exports.create(dict,input,"selected-export",editor,"test");assertThat(result.entryCount()).isEqualTo(1);
        assertThat(exports.create(dict,input,"selected-export",editor,"test").id()).isEqualTo(result.id());
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exports.download(result.id(),editor).bytes()))){
            java.util.zip.ZipEntry entry;String sheet="";while((entry=zip.getNextEntry())!=null)if(entry.getName().equals("xl/worksheets/sheet2.xml"))sheet=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(sheet).contains("=1+1").contains("inlineStr").doesNotContain("<f>").doesNotContain(other.id());
        }
        assertThatThrownBy(()->exports.create(dict,new ExportRequest("WORD",null,"APPROVED",input.entryIds()),"selection-state",editor,"test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->exports.create(dict,new ExportRequest("WORD",null,null,List.of(UUID.randomUUID())),"selection-foreign",editor,"test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->exports.create(dict,new ExportRequest("WORD",null,null,List.of(UUID.fromString(chosen.id()),UUID.fromString(chosen.id()))),"selection-repeat",editor,"test")).isInstanceOf(DomainException.class);
        // Adding optional selection must preserve retries of pre-selection, three-property requests.
        var legacyInput=new ExportRequest("WORD",null,"DRAFT");
        var legacy=exports.create(dict,legacyInput,"legacy-export",editor,"test");
        byte[] legacyJson="{\"kind\":\"WORD\",\"query\":null,\"state\":\"DRAFT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String legacyHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(legacyJson));
        db.update("UPDATE standard.dictionary_export SET dmnd_hash_vl=? WHERE id=?",legacyHash,legacy.id());
        assertThat(exports.create(dict,legacyInput,"legacy-export",editor,"test").id()).isEqualTo(legacy.id());
    }
    @Test void approvedSameTypeScopedTermsGiveExplicitExactEvidenceWithoutSaving(){
        UUID org=org();var editor=actor(org);UUID dict=dict(org,editor);var term=term(dict,"temperature","기온",editor);
        dictionaries.createEntry(dict,new EntryInput("TERM","draft","기온","미승인","NUMBER"),editor,"test");
        UUID foreignOrg=org();var foreignActor=actor(foreignOrg);term(dict(foreignOrg,foreignActor),"foreign","기온",foreignActor);
        var draft=metadata.create(new CreateDataset(org,"s-"+UUID.randomUUID(),"추천 대상","설명",org,null,"PRIVATE"),"dataset-create",editor,"test");
        var field=new FieldDefinition("temperature","TEMPERATURE","기온","설명","NUMBER",true,null,null,0,true,null,null);
        var saved=fields.replace(draft.id(),metadataTag(draft),new FieldsInput(List.of(field)),editor,"test");
        var page=lookup.suggestions(draft.id(),"temperature",0,10,editor);
        assertThat(page.items()).extracting(i->i.id().toString()).containsExactly(term.id());assertThat(page.items().getFirst().evidenceCode()).isEqualTo("DISPLAY_NAME_EXACT");
        assertThat(page.basis().lockVersion()).isEqualTo(saved.lockVersion());assertThat(metadata.getDraft(draft.id(),editor).lockVersion()).isEqualTo(saved.lockVersion());
        assertThat(lookup.search(org,"NUMBER","temperature",0,1,editor).items()).hasSize(1);
        assertThat(lookup.search(org,"STRING",null,0,20,editor).items()).isEmpty();
        assertThatThrownBy(()->lookup.suggestions(draft.id(),"unsaved-new-field",0,10,editor)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->lookup.search(org,"NUMBER",null,0,10,foreignActor)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void recommendationScoresRanksAndDictionaryChangesFollowThePublishedRule(){
        UUID org=org();var editor=actor(org);UUID dict=dict(org,editor);
        var byName=term(dict,"temperature_main","기온",editor);
        var byIdentifier=term(dict,"temperature","다른 이름",editor);
        var draft=metadata.create(new CreateDataset(org,"rank-"+UUID.randomUUID(),"추천 순위","설명",org,null,"PRIVATE"),"rank-create",editor,"test");
        fields.replace(draft.id(),metadataTag(draft),new FieldsInput(List.of(new FieldDefinition("temperature","temperature","기온","설명","NUMBER",true,null,null,0,true,null,null))),editor,"test");
        var first=lookup.suggestions(draft.id(),"temperature",0,1,editor);
        assertThat(first.items()).hasSize(1);assertThat(first.hasNext()).isTrue();
        assertThat(first.items().getFirst().id().toString()).isEqualTo(byName.id());
        assertThat(first.items().getFirst().recommendationScore()).isEqualTo(100);
        assertThat(first.items().getFirst().recommendationRank()).isEqualTo(1);
        assertThat(first.items().getFirst().scoreRule()).contains("term-exact-match-v1");
        assertThat(first.items().getFirst().confidenceLabel()).contains("직접 일치");
        var second=lookup.suggestions(draft.id(),"temperature",1,1,editor).items().getFirst();
        assertThat(second.id().toString()).isEqualTo(byIdentifier.id());
        assertThat(second.recommendationScore()).isEqualTo(90);assertThat(second.recommendationRank()).isEqualTo(2);
        // A newly approved entry must be reflected without mutating immutable decided entries or cached scores.
        var added=term(dict,"temperature_secondary","기온",editor);
        var changed=lookup.suggestions(draft.id(),"temperature",0,10,editor);
        assertThat(changed.items()).hasSize(3);
        assertThat(changed.items()).extracting(item->item.id().toString()).contains(added.id());
        assertThat(changed.items()).extracting(item->item.recommendationScore()).containsExactly(100,100,90);
        assertThat(changed.items().getLast().id().toString()).isEqualTo(byIdentifier.id());
        assertThat(lookup.search(org,"NUMBER","temperature",0,10,editor).items()).allMatch(item->item.recommendationScore()==null&&item.recommendationRank()==null);
    }
    @Test void usageReadsOnlyRequestedActiveOrganizationLatestManagedFields(){
        UUID org=org();var editor=actor(org);UUID dict=dict(org,editor);var term=term(dict,"temperature","기온",editor);UUID termId=UUID.fromString(term.id());
        var draft=metadata.create(new CreateDataset(org,"u-"+UUID.randomUUID(),"비공개 관리 데이터","설명",org,null,"PRIVATE"),"usage-create",editor,"test");
        var field=new FieldDefinition("temperature","temperature","기온","설명","NUMBER",true,null,null,0,false,termId,"시험 사용자 연결");
        var saved=fields.replace(draft.id(),metadataTag(draft),new FieldsInput(List.of(field)),editor,"test");
        var usages=lookup.usages(termId,org,0,1,editor);assertThat(usages.items()).hasSize(1);assertThat(usages.items().getFirst().datasetId()).isEqualTo(draft.id());assertThat(usages.items().getFirst().visibility()).isEqualTo("PRIVATE");
        assertThatThrownBy(()->lookup.usages(termId,org,0,10,actor(org()))).isInstanceOf(AccessDeniedException.class);
        fields.replace(draft.id(),GovernanceSupport.etag("dataset",draft.id().toString(),saved.lockVersion()),new FieldsInput(List.of()),editor,"test");
        assertThat(lookup.usages(termId,org,0,10,editor).items()).isEmpty();
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",org);
        assertThatThrownBy(()->lookup.usages(termId,org,0,10,editor)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void usageSeparatesCurrentManagementFieldsFromImmutablePublicationAndWithdrawal(){
        UUID org=org(),category=UUID.randomUUID();var editor=actor(org);UUID dict=dict(org,editor);
        UUID termId=UUID.fromString(term(dict,"publication_term","게시 구분",editor).id());
        db.update("INSERT INTO classification.category(id,cd,nm) VALUES (?,?,'조회 분류')",category,"usage-"+category);
        var draft=metadata.create(new CreateDataset(org,"u-"+UUID.randomUUID(),"게시 구분 대상","설명",org,category,"PUBLIC",null,null,null,
                new MetadataDetails("시험 담당부서","시험용 이용조건",null,null,null,null,List.of("게시구분"))),"usage-publish-create",editor,"test");
        var field=new FieldDefinition("temperature","temperature","게시 구분","설명","NUMBER",true,null,null,0,true,termId,"시험 연결");
        fields.replace(draft.id(),metadataTag(draft),new FieldsInput(List.of(field)),editor,"test");
        var initial=lookup.usages(termId,org,0,10,editor).items().getFirst();
        assertThat(initial.providerOrganizationName()).isEqualTo("시험기관");assertThat(initial.primaryCategoryName()).isEqualTo("조회 분류");assertThat(initial.revisionUpdatedAt()).isNotNull();assertThat(initial.latestPublication()).isNull();
        var review=metadata.submit(draft.id(),metadataTag(metadata.getDraft(draft.id(),editor)),"usage-submit",editor,"test");
        var reviewer=UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(org)),null,List.of(new SimpleGrantedAuthority("ROLE_ORG_REVIEWER")));
        metadata.decide(review.id(),new DecideReview("APPROVE",null),GovernanceSupport.etag("review",review.id().toString(),review.lockVersion()),"usage-approve",reviewer,"test");
        var publisher=UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(org)),null,List.of(new SimpleGrantedAuthority("ROLE_ORG_PUBLISHER")));
        var approved=metadata.getDraft(draft.id(),editor);
        var published=metadata.publish(draft.id(),new PublishRevision(approved.revisionId(),approved.reviewDecisionId()),metadataTag(approved),"usage-publish",publisher,"test");
        var current=lookup.usages(termId,org,0,10,editor).items().getFirst();assertThat(current.latestPublication().current()).isTrue();assertThat(current.latestPublication().matchesManagementRevision()).isTrue();assertThat(current.latestPublication().publicationNo()).isEqualTo(1);
        var next=metadata.newDraft(draft.id(),metadataTag(metadata.getDraft(draft.id(),editor)),"usage-new-draft",editor,"test");
        var nextUsage=lookup.usages(termId,org,0,10,editor).items().getFirst();assertThat(nextUsage.state()).isEqualTo("DRAFT");assertThat(nextUsage.latestPublication().current()).isTrue();assertThat(nextUsage.latestPublication().matchesManagementRevision()).isFalse();assertThat(nextUsage.revisionNo()).isEqualTo(2);
        metadata.withdraw(published.id(),new Withdrawal("시험 철회"),GovernanceSupport.etag("publication",published.id().toString(),published.lockVersion()),"usage-withdraw",publisher,"test");
        var withdrawn=lookup.usages(termId,org,0,10,editor).items().getFirst();assertThat(withdrawn.latestPublication().state()).isEqualTo("WITHDRAWN");assertThat(withdrawn.latestPublication().current()).isFalse();
        var cleared=new FieldDefinition("temperature","temperature","게시 구분","설명","NUMBER",true,null,null,0,true,null,null);
        fields.replace(draft.id(),metadataTag(metadata.getDraft(draft.id(),editor)),new FieldsInput(List.of(cleared)),editor,"test");
        assertThat(lookup.usages(termId,org,0,10,editor).items()).isEmpty();
        assertThat(db.queryForObject("SELECT mtdt_cn->'fields'->0->>'approvedTermId' FROM catalog.dataset_revision WHERE id=?",String.class,approved.revisionId())).isEqualTo(termId.toString());
        assertThat(next.revisionId()).isNotEqualTo(approved.revisionId());
    }
    @Test void lookupEndpointsRejectAnonymousAndUnboundedRequests() throws Exception {
        UUID org=org();mvc.perform(get("/api/admin/v1/dictionary-terms").param("organizationId",org.toString()).param("dataType","NUMBER")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/v1/dictionary-terms").with(authentication(actor(org))).param("organizationId",org.toString()).param("dataType","NUMBER").param("size","51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/v1/dictionary-terms").with(authentication(actor(org))).param("organizationId",org.toString()).param("dataType","NUMBER").param("size","1")).andExpect(status().isOk());
    }
    @Test void publicSourceReadAndExportsExcludeUnapprovedRowsAndRemainReadOnly() throws Exception {
        UUID publicDictionary=UUID.randomUUID(),approvedId=UUID.randomUUID(),draftId=UUID.randomUUID();var reader=actor(org());
        db.update("INSERT INTO standard.dictionary(id,rng_cd,cd,nm,ver_nm) VALUES (?,'PUBLIC',?,'[시험] 공공 원본','fixture')",publicDictionary,"public-"+publicDictionary);
        db.update("INSERT INTO standard.dictionary_entry(id,stddict_id,type_cd,cd,nm,dfn_cn,data_type_cd,stts_cd,creatr_id,mdfr_id,sbmsn_user_id,dcsn_user_id) VALUES (?,?,'WORD','approved','승인 원본','시험','STRING','APPROVED',?,?,?,?)",approvedId,publicDictionary,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        db.update("INSERT INTO standard.dictionary_entry(id,stddict_id,type_cd,cd,nm,dfn_cn,data_type_cd,creatr_id) VALUES (?,?,'WORD','draft','미승인 원본','시험','STRING',?)",draftId,publicDictionary,UUID.randomUUID());
        assertThat(dictionaries.entries(publicDictionary,"WORD",null,null,0,20,reader)).extracting(DictionaryEntry::id).containsExactly(approvedId.toString());
        mvc.perform(get("/api/admin/v1/dictionary-entries/"+draftId).with(authentication(reader))).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/v1/dictionary-entries/"+draftId+"/relationships").with(authentication(reader))).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/v1/dictionary-entries/"+approvedId).with(authentication(reader))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/v1/dictionary-entries/"+approvedId+"/relationships").with(authentication(reader))).andExpect(status().isOk());
        assertThat(exports.create(publicDictionary,new ExportRequest("WORD",null,null),"public-filtered",reader,"test").entryCount()).isEqualTo(1);
        assertThatThrownBy(()->exports.create(publicDictionary,new ExportRequest("WORD",null,null,List.of(draftId)),"public-draft-reject",reader,"test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->dictionaries.createEntry(publicDictionary,new EntryInput("WORD","illegal","수정","시험","STRING"),reader,"test")).isInstanceOf(DomainException.class);
    }
    private DictionaryEntry term(UUID dictionary,String code,String name,Authentication editor){
        var word=approve(dictionaries.createEntry(dictionary,new EntryInput("WORD","w_"+code,"단어","정의","NUMBER"),editor,"test"),editor);
        var domain=approve(dictionaries.createEntry(dictionary,new EntryInput("DOMAIN","d_"+code,"도메인","정의","NUMBER"),editor,"test"),editor);
        var term=dictionaries.createEntry(dictionary,new EntryInput("TERM",code,name,"정의","NUMBER"),editor,"test");
        relations.term(UUID.fromString(term.id()),tag(term),new TermAttributes(code,UUID.fromString(domain.id()),List.of(UUID.fromString(word.id()))),editor,"test");
        return approve(dictionaries.get(UUID.fromString(term.id()),editor),editor);
    }
    private DictionaryEntry approve(DictionaryEntry entry,Authentication editor){
        UUID id=UUID.fromString(entry.id());var submitted=dictionaries.submit(id,tag(entry),editor,"test");
        var principal=(CatalogPrincipal)editor.getPrincipal();var reviewer=UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),principal.organizationIds()),null,List.of(new SimpleGrantedAuthority("ROLE_ORG_REVIEWER")));
        return dictionaries.decide(id,tag(submitted),new Decision("APPROVE",null),reviewer,"test");
    }
    private UUID dict(UUID org,Authentication actor){return UUID.fromString(dictionaries.create(new DictionaryInput(org,"dict-"+UUID.randomUUID(),"검색 사전","1"),actor,"test").id());}
    private UUID org(){UUID id=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),"시험기관");return id;}
    private Authentication actor(UUID org){return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(org)),null,List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));}
    private String tag(DictionaryEntry e){return GovernanceSupport.etag("dictionary-entry",e.id(),e.lockVersion());}
    private String metadataTag(MetadataDraft d){return GovernanceSupport.etag("dataset",d.id().toString(),d.lockVersion());}
}
