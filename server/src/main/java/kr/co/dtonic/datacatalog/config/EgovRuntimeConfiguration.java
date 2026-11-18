package kr.co.dtonic.datacatalog.config;

import org.egovframe.rte.fdl.cmmn.trace.LeaveaTrace;
import org.egovframe.rte.fdl.cmmn.trace.handler.DefaultTraceHandler;
import org.egovframe.rte.fdl.cmmn.trace.handler.TraceHandler;
import org.egovframe.rte.fdl.cmmn.trace.manager.DefaultTraceHandleManager;
import org.egovframe.rte.fdl.cmmn.trace.manager.TraceHandlerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

/** Required RTE service trace infrastructure; request correlation is handled separately. */
@Configuration
public class EgovRuntimeConfiguration {
    @Bean(name = "leaveaTrace")
    LeaveaTrace leaveaTrace() {
        DefaultTraceHandleManager manager = new DefaultTraceHandleManager();
        manager.setReqExpMatcher(new AntPathMatcher());
        manager.setPatterns(new String[] {"*"});
        manager.setHandlers(new TraceHandler[] {new DefaultTraceHandler()});
        LeaveaTrace trace = new LeaveaTrace();
        trace.setTraceHandlerServices(new TraceHandlerService[] {manager});
        return trace;
    }
}
