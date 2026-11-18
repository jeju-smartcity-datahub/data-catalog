package kr.co.dtonic.datacatalog.config;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Configuration
public class PersistenceConfiguration {
    @Bean(name = "sqlSession")
    SqlSessionFactory sqlSession(DataSource dataSource) throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        // JDBC workflow writes and MyBatis reads share transactions. Never reuse a stale pre-write SELECT.
        configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        configuration.getTypeHandlerRegistry().register(java.util.UUID.class, new UuidTypeHandler());
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));
        return factory.getObject();
    }
}
