package kr.co.dtonic.datacatalog.classification;

import java.util.List;
import java.util.Map;
import kr.co.dtonic.datacatalog.classification.CategoryContracts.*;
import org.egovframe.rte.psl.dataaccess.EgovAbstractMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryMapper extends EgovAbstractMapper {
    public List<CategoryDetails> categories(Map<String,Object> p) { return selectList("Classification.categories",p); }
    public CategoryDetails category(Map<String,Object> p) { return selectOne("Classification.category",p); }
    public void insertCategory(Map<String,Object> p) { insert("Classification.insertCategory",p); }
    public int updateCategory(Map<String,Object> p) { return update("Classification.updateCategory",p); }
    public int deleteCategory(Map<String,Object> p) { return delete("Classification.deleteCategory",p); }
    public long datasetCount(Map<String,Object> p) { return selectOne("Classification.datasetCount",p); }
    public List<CategoryDatasetUsage> datasets(Map<String,Object> p) { return selectList("Classification.datasets",p); }
}
