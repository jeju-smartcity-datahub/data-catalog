package kr.co.dtonic.datacatalog.identity;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

public final class LocalAuthProfiles {
    public static final String EXPRESSION = "(dev | test | prod | production) & local-auth";
    private LocalAuthProfiles() {}
    public static boolean enabled(Environment environment) {
        return environment.acceptsProfiles(Profiles.of(EXPRESSION))
                && (!production(environment) || environment.getProperty("catalog.local-auth.enabled", Boolean.class, false));
    }

    public static boolean production(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("prod | production"));
    }
}
