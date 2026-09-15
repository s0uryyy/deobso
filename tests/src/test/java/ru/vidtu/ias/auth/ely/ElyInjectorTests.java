// SPDX-License-Identifier: LGPL-3.0-or-later
package ru.vidtu.ias.auth.ely;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElyInjectorTests {
    @Test void acceptsOfficialAliases() {
        for (String endpoint : new String[]{"ely.by", "https://ely.by/", "http://ely.by/",
                "HTTPS://ELY.BY:443/", "authserver.ely.by", "https://authserver.ely.by/",
                "https://account.ely.by/api/authlib-injector/", "account.ely.by/api/authlib-injector"}) {
            assertTrue(ElyAuth.elyEndpoint(endpoint), endpoint);
            assertTrue(ElyAuth.elyAgentArgument("-javaagent:C:/Games/My Launcher/agent.jar=" + endpoint), endpoint);
        }
    }

    @Test void rejectsOtherServersAndMisleadingArguments() {
        for (String endpoint : new String[]{"https://ely.by.evil.example", "https://ely.by@evil.example",
                "https://evil.example@ely.by", "https://ely.by:8443", "https://ely.by/other",
                "https://ely.by/?secret=value", "https://ely.by/#fragment", "https://ely.by/%2f",
                "https://account.ely.by", "file:///ely.by", "", "not a uri"}) {
            assertFalse(ElyAuth.elyEndpoint(endpoint), endpoint);
        }
        assertFalse(ElyAuth.elyAgentArgument("-javaagent:agent.jar=https://evil.example/?x=ely.by"));
        assertFalse(ElyAuth.elyAgentArgument("-Dexample=ely.by"));
        assertFalse(ElyAuth.elyAgentArgument("-javaagent:agent.jar"));
        assertFalse(ElyAuth.elyAgentArgument("-javaagent:=ely.by"));
    }

    public static class Active { public static Object getClassTransformer() { return new Object(); } }
    public static class Inactive { public static Object getClassTransformer() { return null; } }
    public static class Broken { public static Object getClassTransformer() { throw new IllegalStateException(); } }

    @Test void requiresInitializedTransformerNotJustClassPresence() {
        assertTrue(ElyAuth.activeInjector(Active.class));
        assertFalse(ElyAuth.activeInjector(Inactive.class));
        assertFalse(ElyAuth.activeInjector(Broken.class));
        assertFalse(ElyAuth.activeInjector(Object.class));
    }

    public static class ElyProfileFixture { }
    public static class PatchedSessionFixture { private ElyProfileFixture profile; }
    public static class VanillaSessionFixture { private Object profile; }

    @Test void recognizesIntegratedReplacementButNotAnUnrelatedElyClass() {
        assertTrue(ElyAuth.integratedReplacement(PatchedSessionFixture.class, ElyProfileFixture.class));
        assertFalse(ElyAuth.integratedReplacement(VanillaSessionFixture.class, ElyProfileFixture.class));
        assertFalse(ElyAuth.integratedReplacement(PatchedSessionFixture.class, Object.class));
        assertFalse(ElyAuth.replacementAvailable(new ClassLoader(null) { }));
    }
}
