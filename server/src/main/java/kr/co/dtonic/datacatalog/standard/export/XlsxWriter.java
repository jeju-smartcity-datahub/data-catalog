package kr.co.dtonic.datacatalog.standard.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal OOXML workbook: strings stay inline strings, including formula-like and zero-prefixed values. */
public final class XlsxWriter {
    private XlsxWriter() {}
    public static byte[] write(List<List<String>> provenance,List<List<String>> entries){
        try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
            put(zip,"[Content_Types].xml","""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """);
            put(zip,"_rels/.rels","""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """);
            put(zip,"xl/workbook.xml","""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="추출 정보" sheetId="1" r:id="rId1"/><sheet name="사전 항목" sheetId="2" r:id="rId2"/></sheets>
                </workbook>
                """);
            put(zip,"xl/_rels/workbook.xml.rels","""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """);
            put(zip,"xl/styles.xml","""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
                  <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF184E77"/><bgColor indexed="64"/></patternFill></fill></fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf></cellXfs>
                  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                </styleSheet>
                """);
            put(zip,"xl/worksheets/sheet1.xml",sheet(provenance));put(zip,"xl/worksheets/sheet2.xml",sheet(entries));
            zip.finish();return bytes.toByteArray();
        }catch(IOException e){throw new IllegalStateException("Cannot write workbook",e);}
    }
    private static String sheet(List<List<String>> rows){
        int width=rows.isEmpty()?1:rows.getFirst().size();
        var out=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        out.append("<dimension ref=\"A1:").append(column(width-1)).append(Math.max(1,rows.size())).append("\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/><cols>");
        for(int c=1;c<=width;c++)out.append("<col min=\"").append(c).append("\" max=\"").append(c).append("\" width=\"").append(c==4?64:28).append("\" customWidth=\"1\"/>");
        out.append("</cols><sheetData>");
        for(int i=0;i<rows.size();i++){
            out.append("<row r=\"").append(i+1).append("\">");var row=rows.get(i);
            for(int c=0;c<row.size();c++)out.append("<c r=\"").append(column(c)).append(i+1).append("\" t=\"inlineStr\" s=\"").append(i==0?1:0).append("\"><is><t xml:space=\"preserve\">").append(xml(row.get(c))).append("</t></is></c>");
            out.append("</row>");
        }
        return out.append("</sheetData><autoFilter ref=\"A1:").append(column(width-1)).append(Math.max(1,rows.size())).append("\"/></worksheet>").toString();
    }
    private static String column(int index){var value=new StringBuilder();for(int n=index+1;n>0;n=(n-1)/26)value.insert(0,(char)('A'+(n-1)%26));return value.toString();}
    private static String xml(String value){
        if(value==null)return "";var safe=new StringBuilder();value.codePoints().filter(c->c==9||c==10||c==13||c>=32&&c<=0xD7FF||c>=0xE000&&c<=0xFFFD||c>=0x10000&&c<=0x10FFFF).forEach(safe::appendCodePoint);
        return safe.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }
    private static void put(ZipOutputStream zip,String path,String content)throws IOException{var entry=new ZipEntry(path);entry.setTime(0);zip.putNextEntry(entry);zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
