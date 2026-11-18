package kr.co.dtonic.datacatalog;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import kr.co.dtonic.datacatalog.standard.export.XlsxWriter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class XlsxWriterTest {
    @Test void workbookIsParseableAndFormulaLikeValuesRemainLiteralStrings()throws Exception{
        byte[] bytes=XlsxWriter.write(List.of(List.of("항목","값"),List.of("버전","2026.10")),
                List.of(List.of("코드","이름","정의"),List.of("001","=HYPERLINK(\"https://invalid\")","<&> 한글\u0001😀")));
        var entries=new HashMap<String,String>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())entries.put(entry.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));
        }
        assertThat(entries).containsKeys("[Content_Types].xml","_rels/.rels","xl/workbook.xml","xl/_rels/workbook.xml.rels","xl/styles.xml","xl/worksheets/sheet1.xml","xl/worksheets/sheet2.xml");
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setNamespaceAware(true);
        for(String xml:entries.values())assertThatCode(()->factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))).doesNotThrowAnyException();
        var sheet=entries.get("xl/worksheets/sheet2.xml");
        assertThat(sheet).contains("t=\"inlineStr\"","001","=HYPERLINK(&quot;https://invalid&quot;)","&lt;&amp;&gt; 한글😀")
                .doesNotContain("<f>","<f ","hyperlink", "\u0001");
        assertThat(entries.get("xl/_rels/workbook.xml.rels")).doesNotContain("TargetMode=\"External\"");
    }
}
