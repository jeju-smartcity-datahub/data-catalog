package kr.co.dtonic.datacatalog;

import java.util.*;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.application.DictionaryService;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipModels.*;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipService;
import kr.co.dtonic.datacatalog.standard.export.DictionaryExportModels.*;
import kr.co.dtonic.datacatalog.standard.export.DictionaryExportService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres") @SpringBootTest @ActiveProfiles("test") @Transactional
class DictionaryRelationsPostgresIT {
    @Autowired JdbcTemplate db;@Autowired DictionaryService dictionary;@Autowired DictionaryRelationshipService relations;@Autowired DictionaryExportService exports;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){PostgresDatabase.configure(registry);}
    @Test void orderedApprovedWordAndDomainRelationsBecomeImmutableWithTermApproval(){
        UUID org=organization();var editor=actor(org,"ORG_EDITOR");var reviewer=actor(org,"ORG_REVIEWER");UUID dict=createDictionary(org,editor);
        var word=approved(dict,"WORD","word",editor,reviewer);var domain=approved(dict,"DOMAIN","number",editor,reviewer);
        var term=dictionary.createEntry(dict,input("TERM","temperature"),editor,"relation-test");UUID id=UUID.fromString(term.id());
        assertThatThrownBy(()->dictionary.submit(id,tag(term),editor,"relation-test")).isInstanceOf(DomainException.class).hasMessageContaining("도메인");
        var saved=relations.term(id,tag(term),new TermAttributes("temperature",UUID.fromString(domain.id()),List.of(UUID.fromString(word.id()),UUID.fromString(word.id()))),editor,"relation-test");
        assertThat(saved.term().wordEntryIds()).containsExactly(UUID.fromString(word.id()),UUID.fromString(word.id()));
        var submitted=dictionary.submit(id,tag(id,saved.lockVersion()),editor,"relation-test");
        var approved=dictionary.decide(id,tag(submitted),new Decision("APPROVE",null),reviewer,"relation-test");
        assertThat(approved.state()).isEqualTo("APPROVED");
        assertThatThrownBy(()->db.update("UPDATE standard.term_definition SET trm_eng_abbr_nm='tampered' WHERE artcl_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void rejectsCrossOrganizationUnapprovedReferencesAndDatatypeMismatch(){
        UUID org=organization(),other=organization();var editor=actor(org,"ORG_EDITOR");var reviewer=actor(org,"ORG_REVIEWER");UUID dict=createDictionary(org,editor);
        var word=approved(dict,"WORD","word",editor,reviewer);var draftDomain=dictionary.createEntry(dict,input("DOMAIN","draft"),editor,"relation-test");
        var term=dictionary.createEntry(dict,input("TERM","term"),editor,"relation-test");UUID id=UUID.fromString(term.id());
        assertThatThrownBy(()->relations.term(id,tag(term),new TermAttributes("field",UUID.fromString(draftDomain.id()),List.of(UUID.fromString(word.id()))),editor,"relation-test"))
                .isInstanceOf(DomainException.class).hasMessageContaining("승인된");
        var otherEditor=actor(other,"ORG_EDITOR");UUID otherDict=createDictionary(other,otherEditor);
        var otherDomain=approved(otherDict,"DOMAIN","other",otherEditor,actor(other,"ORG_REVIEWER"));
        assertThatThrownBy(()->relations.term(id,tag(term),new TermAttributes("field",UUID.fromString(otherDomain.id()),List.of(UUID.fromString(word.id()))),editor,"relation-test"))
                .isInstanceOf(DomainException.class).hasMessageContaining("다른 기관");
        var stringTerm=dictionary.createEntry(dict,new EntryInput("TERM","string-term","문자 용어","설명","STRING"),editor,"relation-test");
        var number=approved(dict,"DOMAIN","number",editor,reviewer);
        assertThatThrownBy(()->relations.term(UUID.fromString(stringTerm.id()),tag(stringTerm),new TermAttributes("field",UUID.fromString(number.id()),List.of(UUID.fromString(word.id()))),editor,"relation-test"))
                .isInstanceOf(DomainException.class).hasMessageContaining("타입");
    }
    @Test void wordAndDomainAttributesCheckAggregateVersionAndType(){
        UUID org=organization();var editor=actor(org,"ORG_EDITOR");UUID dict=createDictionary(org,editor);
        var word=dictionary.createEntry(dict,input("WORD","word"),editor,"relation-test");UUID id=UUID.fromString(word.id());
        relations.word(id,tag(word),new WordAttributes("temperature","TEMP",List.of("기온")),editor,"relation-test");
        assertThatThrownBy(()->relations.word(id,tag(word),new WordAttributes("changed","T",List.of()),editor,"relation-test")).isInstanceOf(DomainException.class).hasMessageContaining("다른 사용자");
        var domain=dictionary.createEntry(dict,input("DOMAIN","number"),editor,"relation-test");
        assertThatThrownBy(()->relations.domain(UUID.fromString(domain.id()),tag(domain),new DomainAttributes(20,null,null,null,null,List.of()),editor,"relation-test"))
                .isInstanceOf(DomainException.class).hasMessageContaining("문자열");
        var saved=relations.domain(UUID.fromString(domain.id()),tag(domain),new DomainAttributes(null,10,2,null,"C",List.of()),editor,"relation-test");
        assertThat(saved.domain().precision()).isEqualTo(10);assertThat(saved.lockVersion()).isEqualTo(1);
    }
    @Test void xlsxExportsFixedVersionAndFiltersReplayIdenticallyAndCheckScopeAndExpiry(){
        UUID org=organization();var editor=actor(org,"ORG_EDITOR");UUID dict=createDictionary(org,editor);
        dictionary.createEntry(dict,new EntryInput("WORD","safe-code","=HYPERLINK(\"bad\")","<정의>& 내용","STRING"),editor,"export-test");
        dictionary.createEntry(dict,new EntryInput("DOMAIN","excluded","다른 종류","설명","STRING"),editor,"export-test");
        var input=new ExportRequest("WORD",null,"DRAFT");var first=exports.create(dict,input,"export-key-123",editor,"export-test");
        var repeated=exports.create(dict,input,"export-key-123",editor,"export-test");
        assertThat(repeated).isEqualTo(first);assertThat(first.entryCount()).isEqualTo(1);
        var file=exports.download(first.id(),editor);assertThat(file.bytes()).startsWith((byte)'P',(byte)'K');
        assertThatThrownBy(()->exports.create(dict,new ExportRequest("DOMAIN",null,null),"export-key-123",editor,"export-test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->exports.download(first.id(),actor(organization(),"ORG_EDITOR"))).isInstanceOf(AccessDeniedException.class);
        db.update("UPDATE standard.dictionary_export SET expry_dt=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",first.id());
        assertThatThrownBy(()->exports.download(first.id(),editor)).isInstanceOf(DomainException.class).hasMessageContaining("만료");
    }
    @Test void xlsxExportsTenThousandRowsAndRejectsOneMoreWithoutTruncating() throws Exception {
        UUID org=organization();var editor=actor(org,"ORG_EDITOR");UUID dict=createDictionary(org,editor);
        db.update("""
            INSERT INTO standard.dictionary_entry(id,stddict_id,type_cd,cd,nm,dfn_cn,data_type_cd,stts_cd,creatr_id)
            SELECT gen_random_uuid(),?,'WORD','word-'||lpad(n::text,5,'0'),'단어 '||n,'다운로드 경계 시험','STRING','DRAFT',?
            FROM generate_series(1,10000) AS n
            """,dict,((CatalogPrincipal)editor.getPrincipal()).userId());
        var exported=exports.create(dict,new ExportRequest("WORD",null,"DRAFT"),"export-boundary-10000",editor,"export-test");
        assertThat(exported.entryCount()).isEqualTo(10000);
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exports.download(exported.id(),editor).bytes()))){
            java.util.zip.ZipEntry entry;String sheet="";
            while((entry=zip.getNextEntry())!=null)if(entry.getName().equals("xl/worksheets/sheet2.xml"))sheet=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(sheet).contains("word-00001").contains("word-10000");
            assertThat(java.util.regex.Pattern.compile("<row\\b").matcher(sheet).results().count()).isEqualTo(10001);
        }
        dictionary.createEntry(dict,new EntryInput("WORD","word-10001","추가 단어","다운로드 경계 시험","STRING"),editor,"export-test");
        assertThatThrownBy(()->exports.create(dict,new ExportRequest("WORD",null,"DRAFT"),"export-boundary-10001",editor,"export-test"))
                .isInstanceOf(DomainException.class).hasMessageContaining("10,000개");
        assertThat(db.queryForObject("SELECT count(*) FROM standard.dictionary_export WHERE stddict_id=?",Integer.class,dict)).isEqualTo(1);
    }
    @Test void databasePreventsTermSubmissionWithoutRelations(){
        UUID org=organization();var editor=actor(org,"ORG_EDITOR");UUID dict=createDictionary(org,editor);
        var term=dictionary.createEntry(dict,input("TERM","term"),editor,"relation-test");
        assertThatThrownBy(()->db.update("UPDATE standard.dictionary_entry SET stts_cd='IN_REVIEW',sbmsn_user_id=? WHERE id=?",UUID.randomUUID(),UUID.fromString(term.id())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    private DictionaryEntry approved(UUID dict,String kind,String code,Authentication editor,Authentication reviewer){
        var entry=dictionary.createEntry(dict,input(kind,code),editor,"relation-test");UUID id=UUID.fromString(entry.id());
        var submitted=dictionary.submit(id,tag(entry),editor,"relation-test");return dictionary.decide(id,tag(submitted),new Decision("APPROVE",null),reviewer,"relation-test");
    }
    private EntryInput input(String kind,String code){return new EntryInput(kind,code,"시험 "+code,"검증 정의","NUMBER");}
    private UUID createDictionary(UUID org,Authentication editor){return UUID.fromString(dictionary.create(new DictionaryInput(org,"dict-"+UUID.randomUUID(),"[시험] 관계 사전","2026.10"),editor,"relation-test").id());}
    private UUID organization(){UUID id=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),"시험 기관");return id;}
    private Authentication actor(UUID org,String role){return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(org)),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    private String tag(DictionaryEntry entry){return tag(UUID.fromString(entry.id()),entry.lockVersion());}
    private String tag(UUID id,long version){return GovernanceSupport.etag("dictionary-entry",id.toString(),version);}
}
