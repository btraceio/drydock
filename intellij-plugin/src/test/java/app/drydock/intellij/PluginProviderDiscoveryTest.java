package app.drydock.intellij;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * AC6 discovery half: all four providers must be discoverable through the
 * classloader of the Drydock plugin as loaded in a booted IDE, not just on a
 * plain test classpath. The thread-context classloader inside an IDE is the
 * application loader, which cannot see the plugin's own META-INF/services
 * registrations, so the registry is built with the loader taken from the
 * plugin descriptor.
 */
public class PluginProviderDiscoveryTest extends BasePlatformTestCase {

    public void testDiscoversAllProvidersUnderThePluginClassLoader() throws Exception {
        PluginDescriptor drydock = PluginManagerCore.getPlugin(PluginId.getId("io.btrace.drydock"));
        assertNotNull("Drydock plugin is not loaded in the test IDE sandbox", drydock);
        ClassLoader pluginLoader = drydock.getPluginClassLoader();
        AgentContext ctx = new AgentContext(
                Files.createTempDirectory("drydock-state"),
                Files.createTempDirectory("drydock-activity"),
                ForkJoinPool.commonPool());
        List<AgentKind> discovered = AgentRegistry.create(ctx, pluginLoader).agents().stream()
                .map(Agent::kind)
                .toList();
        assertEquals(AgentKind.preferenceOrder(), discovered);
    }
}
