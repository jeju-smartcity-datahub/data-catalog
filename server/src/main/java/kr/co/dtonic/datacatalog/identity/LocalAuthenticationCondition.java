package kr.co.dtonic.datacatalog.identity;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class LocalAuthenticationCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return LocalAuthProfiles.enabled(context.getEnvironment());
    }
}
