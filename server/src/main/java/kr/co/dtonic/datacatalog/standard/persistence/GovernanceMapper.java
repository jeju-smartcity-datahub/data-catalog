package kr.co.dtonic.datacatalog.standard.persistence;

import java.util.List;
import java.util.Map;
import org.egovframe.rte.psl.dataaccess.EgovAbstractMapper;
import org.springframework.stereotype.Repository;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;

@Repository
public class GovernanceMapper extends EgovAbstractMapper {
    public List<Category> categories() { return selectList("Governance.categories"); }
    public Category category(String id) { return selectOne("Governance.category", id); }
    public void insertCategory(Map<String,Object> p) { insert("Governance.insertCategory",p); }
    public int updateCategory(Map<String,Object> p) { return update("Governance.updateCategory",p); }
    public int deleteCategory(Map<String,Object> p) { return delete("Governance.deleteCategory",p); }
    public List<Dictionary> dictionaries(Map<String,Object> p) { return selectList("Governance.dictionaries",p); }
    public Dictionary dictionary(String id) { return selectOne("Governance.dictionary",id); }
    public void insertDictionary(Map<String,Object> p) { insert("Governance.insertDictionary",p); }
    public List<DictionaryEntry> entries(Map<String,Object> p) { return selectList("Governance.entries",p); }
    public DictionaryEntry entry(String id) { return selectOne("Governance.entry",id); }
    public void insertEntry(Map<String,Object> p) { insert("Governance.insertEntry",p); }
    public int updateEntry(Map<String,Object> p) { return update("Governance.updateEntry",p); }
    public int transitionEntry(Map<String,Object> p) { return update("Governance.transitionEntry",p); }
    public void audit(Map<String,Object> p) { insert("Governance.audit",p); }
    public void analysis(Map<String,Object> p) { insert("Governance.analysis",p); }
    public boolean activeOrganization(String id) { return Boolean.TRUE.equals(selectOne("Governance.activeOrganization",id)); }
}
