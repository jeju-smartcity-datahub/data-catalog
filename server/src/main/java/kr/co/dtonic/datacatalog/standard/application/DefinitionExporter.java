package kr.co.dtonic.datacatalog.standard.application;

import java.util.List;
import kr.co.dtonic.datacatalog.common.CsvCells;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.FieldDefinition;

/** CSV cells are quoted and formula-leading characters (including hidden prefixes) neutralized. */
public final class DefinitionExporter {
    private DefinitionExporter() {}
    public static String csv(List<FieldDefinition> fields) {
        var csv=new StringBuilder("\uFEFFfieldPath,physicalName,displayName,description,dataType,nullable,maxLength,unit,ordinal,publicVisible,approvedTermId,mappingEvidence\r\n");
        for(var f:fields) {
            List<String> values=java.util.Arrays.asList(f.fieldPath(),f.physicalName(),f.displayName(),f.description(),f.dataType(),Boolean.toString(f.nullable()),
                    f.maxLength()==null?"":f.maxLength().toString(),f.unit(),Integer.toString(f.ordinal()),Boolean.toString(f.publicVisible()),
                    f.approvedTermId()==null?"":f.approvedTermId().toString(),f.mappingEvidence());
            csv.append(values.stream().map(DefinitionExporter::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return csv.toString();
    }
    public static String cell(String value) {
        return CsvCells.quote(value);
    }
}
