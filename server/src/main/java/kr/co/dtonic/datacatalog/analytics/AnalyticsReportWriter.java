package kr.co.dtonic.datacatalog.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.co.dtonic.datacatalog.common.CsvCells;
import kr.co.dtonic.datacatalog.common.DomainException;
import org.springframework.http.HttpStatus;

/** Bounded UTF-8 serialization and spreadsheet formula-neutral CSV cells. */
final class AnalyticsReportWriter {
    private AnalyticsReportWriter() {}
    static final int BYTE_LIMIT=8*1024*1024;
    static byte[] json(ObjectMapper json,Object document) {
        var output=new BoundedOutput();
        try { json.writeValue(output,document); return output.bytes(); }
        catch(IOException e) { throw serializationFailure(e); }
    }
    static byte[] csv(List<String> headers,List<List<String>> rows) {
        var output=new BoundedOutput();
        try(Writer writer=new OutputStreamWriter(output,StandardCharsets.UTF_8)) {
            writer.write('\uFEFF'); writeRow(writer,headers);
            for(var row:rows)writeRow(writer,row);
        } catch(IOException e) { throw serializationFailure(e); }
        return output.bytes();
    }
    private static void writeRow(Writer writer,List<String> row) throws IOException {
        for(int i=0;i<row.size();i++) {
            if(i>0)writer.write(',');
            writer.write(CsvCells.quote(row.get(i)));
        }
        writer.write("\r\n");
    }
    static DomainException limit() { return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,"REPORT_LIMIT_EXCEEDED","보고서는 최대 5,000행·8MiB입니다. 조건을 좁혀주세요."); }
    private static RuntimeException serializationFailure(IOException error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof SizeLimitException)return limit();
        return new IllegalStateException("Analytics report serialization failed",error);
    }
    private static final class SizeLimitException extends IOException {}
    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        @Override public void write(int value) throws IOException { if(output.size()>=BYTE_LIMIT)throw new SizeLimitException();output.write(value); }
        @Override public void write(byte[] value,int start,int length) throws IOException { if(length>BYTE_LIMIT-output.size())throw new SizeLimitException();output.write(value,start,length); }
        byte[] bytes() { return output.toByteArray(); }
    }
}
