package kr.co.dtonic.datacatalog.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class SearchFeedbackRequestFilterTest {
    private final SearchFeedbackRequestFilter filter=new SearchFeedbackRequestFilter(new ApiProblems(new ObjectMapper()));
    private MockHttpServletRequest request(){var request=new MockHttpServletRequest("POST",SearchFeedbackRequestFilter.PATH);request.setScheme("https");request.setServerName("catalog.test");request.setServerPort(443);request.setContentType("application/json");request.addHeader("Origin","https://catalog.test");return request;}
    @Test void sameOriginJsonPassesWithoutWeakeningCsrfFilter()throws Exception{
        var request=request();request.setContent("{}".getBytes(StandardCharsets.UTF_8));var called=new AtomicBoolean();
        filter.doFilter(request,new MockHttpServletResponse(),(a,b)->called.set(true));assertThat(called).isTrue();
    }
    @Test void foreignOrMissingOriginIsRejected()throws Exception{
        for(String origin:new String[]{"https://other.test","http://catalog.test","https://catalog.test:444","https://user:secret@catalog.test","null"}){
            var request=request();request.removeHeader("Origin");request.addHeader("Origin",origin);var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(a,b)->{throw new AssertionError("Must not reach endpoint");});assertThat(response.getStatus()).isEqualTo(403);
        }
        var request=request();request.removeHeader("Origin");var response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->{throw new AssertionError();});assertThat(response.getStatus()).isEqualTo(403);
    }
    @Test void oversizedAndNonJsonInputsAreRejectedBeforeDeserialization()throws Exception{
        var request=request();request.setContent(new byte[2049]);var response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->{throw new AssertionError();});assertThat(response.getStatus()).isEqualTo(413);
        request=request();request.setContentType("text/plain");response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->{throw new AssertionError();});assertThat(response.getStatus()).isEqualTo(415);
    }
    @Test void fetchSiteMustRemainSameOriginWhenPresent()throws Exception{
        var request=request();request.addHeader("Sec-Fetch-Site","cross-site");var response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->{throw new AssertionError();});assertThat(response.getStatus()).isEqualTo(403);
    }
}
