package kr.co.dtonic.datacatalog.standard.definition;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.egovframe.rte.psl.dataaccess.EgovAbstractMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DefinitionImportMapper extends EgovAbstractMapper {
    public record ImportRow(String id,String organizationId,String datasetId,String sourceHash,int sourceSize,String parserVersion,
            String status,int fieldCount,String warnings,OffsetDateTime createdAt,String analysisRunId,String diagnostics) {}
    public record AnalysisDiagnosticsRow(String organizationId,String diagnostics) {}
    public record FieldRow(String id,String sourcePointer,String fieldDefinition) {}
    public record ApplicationRow(String requestHash,String result) {}
    public void insertImport(Map<String,Object> p) { insert("DefinitionImport.insertImport",p); }
    public void insertField(Map<String,Object> p) { insert("DefinitionImport.insertField",p); }
    public void insertAnalysis(Map<String,Object> p) { insert("DefinitionImport.insertAnalysis",p); }
    public void insertApplication(Map<String,Object> p) { insert("DefinitionImport.insertApplication",p); }
    public ImportRow find(String id) { return selectOne("DefinitionImport.find",id); }
    public AnalysisDiagnosticsRow analysisDiagnostics(String id) { return selectOne("DefinitionImport.analysisDiagnostics",id); }
    public List<FieldRow> fields(String id) { return selectList("DefinitionImport.fields",id); }
    public ApplicationRow application(Map<String,Object> p) { return selectOne("DefinitionImport.application",p); }
    public void lockApplication(String key) { selectOne("DefinitionImport.lockApplication",key); }
}
