package kr.co.dtonic.datacatalog.analytics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only the small same-origin JSON rating endpoint; existing CSRF protection remains enabled. */
public final class SearchFeedbackRequestFilter extends OncePerRequestFilter {
    public static final String PATH="/api/catalog/v1/search-feedback";
    private static final int MAX_BYTES=2048;
    private final ApiProblems problems;
    public SearchFeedbackRequestFilter(ApiProblems problems){this.problems=problems;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !PATH.equals(request.getRequestURI().substring(request.getContextPath().length()))||!"POST".equals(request.getMethod());}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        response.setHeader("Cache-Control","no-store");
        if(!sameOrigin(request)||request.getHeader("Sec-Fetch-Site")!=null&&!"same-origin".equals(request.getHeader("Sec-Fetch-Site"))){
            problems.write(request,response,HttpStatus.FORBIDDEN,"SEARCH_FEEDBACK_ORIGIN_DENIED","같은 카탈로그 화면에서 평가를 요청하세요.");return;
        }
        try{if(request.getContentType()==null||!MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())))throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){problems.write(request,response,HttpStatus.UNSUPPORTED_MEDIA_TYPE,"SEARCH_FEEDBACK_JSON_REQUIRED","평가는 JSON 형식만 지원합니다.");return;}
        if(request.getContentLengthLong()>MAX_BYTES){problems.write(request,response,HttpStatus.PAYLOAD_TOO_LARGE,"SEARCH_FEEDBACK_TOO_LARGE","평가 요청이 너무 큽니다.");return;}
        chain.doFilter(new HttpServletRequestWrapper(request){
            private ServletInputStream stream;
            @Override public ServletInputStream getInputStream()throws IOException{
                if(stream==null){var original=super.getInputStream();stream=new ServletInputStream(){
                    private int count;
                    private int checked(int value)throws IOException{if(value>=0&&++count>MAX_BYTES)throw new IOException("Search feedback request size exceeded");return value;}
                    @Override public int read()throws IOException{return checked(original.read());}
                    @Override public int read(byte[] b,int off,int len)throws IOException{
                        int got=original.read(b,off,Math.min(len,MAX_BYTES-count+1));
                        if(got>0){count+=got;if(count>MAX_BYTES)throw new IOException("Search feedback request size exceeded");}return got;
                    }
                    @Override public boolean isFinished(){return original.isFinished();}
                    @Override public boolean isReady(){return original.isReady();}
                    @Override public void setReadListener(ReadListener listener){original.setReadListener(listener);}
                };}return stream;
            }
            @Override public BufferedReader getReader()throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
    static boolean sameOrigin(HttpServletRequest request){
        try{
            String header=request.getHeader("Origin");if(header==null||header.length()>400)return false;
            URI origin=URI.create(header);
            if(origin.getHost()==null||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null||!origin.getRawPath().isEmpty())return false;
            if(!origin.getScheme().equalsIgnoreCase(request.getScheme())||!origin.getHost().equalsIgnoreCase(request.getServerName()))return false;
            int port=origin.getPort()<0?("https".equalsIgnoreCase(origin.getScheme())?443:80):origin.getPort();
            return port==request.getServerPort();
        }catch(RuntimeException invalid){return false;}
    }
}
