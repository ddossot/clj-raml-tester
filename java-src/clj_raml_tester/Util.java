
package clj_raml_tester;

import guru.nidi.ramlproxy.core.ServerOptions;
import guru.nidi.ramltester.RamlDefinition;

/**
 * Helper class with methods used to bypass ClassNotFoundException during reflective calls on
 * classes that have import statements on optional dependencies (like spring-test).
 */
public class Util
{
    public static RamlDefinition fetchRamlDefinition(final ServerOptions options)
    {
        return options.fetchRamlDefinition();
    }
}
