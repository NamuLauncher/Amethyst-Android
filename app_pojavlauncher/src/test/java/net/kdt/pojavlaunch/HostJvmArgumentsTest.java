package net.kdt.pojavlaunch;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class HostJvmArgumentsTest {
    @Test
    public void absentHostArgumentsKeepRuntimeLoggingConfiguration() {
        List<String> arguments = new ArrayList<>(Arrays.asList(
                "-Dlog4j.configurationFile=/runtime/security.xml",
                "-Dlog4j2.formatMsgNoLookups=true"
        ));
        List<String> expected = new ArrayList<>(arguments);

        HostJvmArguments.appendTo(arguments, null);
        assertEquals(expected, arguments);

        HostJvmArguments.appendTo(arguments, new String[0]);
        assertEquals(expected, arguments);
    }

    @Test
    public void unrelatedHostArgumentsKeepTheirOrderAndExistingValues() {
        List<String> arguments = new ArrayList<>(Arrays.asList(
                "-Dlog4j.configurationFile=/runtime/security.xml",
                "-Xmx1024M"
        ));

        HostJvmArguments.appendTo(arguments, new String[] {
                "-Xmx2048M",
                "-Dlog4j.configurationFileExtra=unrelated",
                "-Dexample=host"
        });

        assertEquals(Arrays.asList(
                "-Dlog4j.configurationFile=/runtime/security.xml",
                "-Xmx1024M",
                "-Xmx2048M",
                "-Dlog4j.configurationFileExtra=unrelated",
                "-Dexample=host"
        ), arguments);
    }

    @Test
    public void lastHostLoggingArgumentReplacesDefaultsWithoutSplittingItsPath() {
        List<String> arguments = new ArrayList<>(Arrays.asList(
                "-Dlog4j.configurationFile=/runtime/security.xml",
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Dlog4j.configurationFile=/version/client.xml",
                "-Dexample=runtime"
        ));

        HostJvmArguments.appendTo(arguments, new String[] {
                "-Dlog4j.configurationFile=/host/old.xml",
                "-Dexample=host",
                "-Dlog4j.configurationFile=/private files/game/client.xml",
                "-Dafter=preserved"
        });

        assertEquals(Arrays.asList(
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Dexample=runtime",
                "-Dexample=host",
                "-Dlog4j.configurationFile=/private files/game/client.xml",
                "-Dafter=preserved"
        ), arguments);
    }

    @Test
    public void emptyLoggingPropertyUsesTheSameLastHostValueRule() {
        List<String> arguments = new ArrayList<>(Arrays.asList(
                "-Dlog4j.configurationFile=/runtime/security.xml"
        ));

        HostJvmArguments.appendTo(arguments, new String[] {
                "-Dlog4j.configurationFile=",
                "-Dlog4j.configurationFile"
        });

        assertEquals(Arrays.asList("-Dlog4j.configurationFile"), arguments);
    }
}
