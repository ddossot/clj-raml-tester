
package clj_raml_tester;

import guru.nidi.ramlproxy.core.ServerOptions;
import guru.nidi.ramltester.RamlDefinition;
import guru.nidi.ramltester.core.RamlReport;

import java.util.List;

/**
 * Helper class with methods used to bypass ClassNotFoundException during reflective calls on
 * classes that have import statements on optional dependencies (like spring-test).
 */
public class Util
{
    public static List<String> validateServerOptions(final ServerOptions options)
    {
        final RamlDefinition definition = options.fetchRamlDefinition();
        final RamlReport validate = options.validateRaml(definition);
        return validate.getValidationViolations().asList();
    }
}
