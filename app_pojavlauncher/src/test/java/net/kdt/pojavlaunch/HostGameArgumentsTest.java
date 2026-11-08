package net.kdt.pojavlaunch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class HostGameArgumentsTest {
    @Test
    public void absentHostArgumentsDoNotAddAConnection() {
        List<String> arguments = baseArguments();

        HostGameArguments.appendTo(arguments, null, modernArguments());
        HostGameArguments.appendTo(arguments, new String[0], modernArguments());

        assertEquals(baseArguments(), arguments);
    }

    @Test
    public void legacyMetadataKeepsConnectionAndUnrelatedArgumentsUnchanged() {
        String[] host = {
                "--test-path", "/private files/test",
                "--server", "play.example.invalid",
                "--port", "25570",
                "--after", "unchanged"
        };
        String[] original = host.clone();
        List<String> expected = baseArguments();
        expected.addAll(Arrays.asList(host));

        for (Object[] metadata : new Object[][] {null, new Object[0], {"--username", "${auth_player_name}"}}) {
            List<String> arguments = baseArguments();
            HostGameArguments.appendTo(arguments, host, metadata);
            assertEquals(expected, arguments);
        }
        assertArrayEquals(original, host);
    }

    @Test
    public void featureListConvertsLegacyIntentAndPreservesUnrelatedArgumentOrder() {
        String[] host = {
                "--test-path", "/private files/test",
                "--port", "25570",
                "--between", "one value",
                "--server", "play.example.invalid",
                "--after", "unchanged"
        };
        String[] original = host.clone();
        List<String> arguments = baseArguments();

        HostGameArguments.appendTo(arguments, host, modernArguments());

        assertEquals(Arrays.asList(
                "--username",
                "TestPlayer",
                "--test-path",
                "/private files/test",
                "--between",
                "one value",
                "--quickPlayMultiplayer",
                "play.example.invalid:25570",
                "--after",
                "unchanged"
        ), arguments);
        assertArrayEquals(original, host);
    }

    @Test
    public void exactDirectAndFeatureStringCapabilitiesAreRecognized() {
        Object[][] metadataCases = {
                {"--quickPlayMultiplayer", "${quickPlayMultiplayer}"},
                {Collections.singletonMap("value", "--quickPlayMultiplayer")}
        };
        for (Object[] metadata : metadataCases) {
            List<String> arguments = new ArrayList<>();
            HostGameArguments.appendTo(arguments, connection("play.example.invalid", "1"), metadata);
            assertEquals(Arrays.asList("--quickPlayMultiplayer", "play.example.invalid:1"), arguments);
        }
    }

    @Test
    public void otherQuickPlayFeaturesAndSubstringMatchesDoNotEnableMultiplayer() {
        Object[] metadata = {
                "--quickPlayMultiplayerExtra",
                Collections.singletonMap("value", "prefix--quickPlayMultiplayer"),
                Collections.singletonMap("value", Arrays.asList("--quickPlaySingleplayer", "${world}")),
                Collections.singletonMap("rules", Collections.singletonMap("--quickPlayMultiplayer", true))
        };
        String[] host = connection("play.example.invalid", "25565");
        List<String> arguments = new ArrayList<>();

        HostGameArguments.appendTo(arguments, host, metadata);

        assertEquals(Arrays.asList(host), arguments);
    }

    @Test
    public void ipv6UsesBracketsForModernEndpointAndKeepsLegacyHostValue() {
        for (String host : new String[] {"2001:db8::1", "[2001:db8::1]"}) {
            List<String> modern = new ArrayList<>();
            HostGameArguments.appendTo(modern, connection(host, "65535"), modernArguments());
            assertEquals(Arrays.asList("--quickPlayMultiplayer", "[2001:db8::1]:65535"), modern);

            List<String> legacy = new ArrayList<>();
            HostGameArguments.appendTo(legacy, connection(host, "25565"), null);
            assertEquals(Arrays.asList(connection(host, "25565")), legacy);
        }
    }

    @Test
    public void alreadyModernAndUnrelatedOnlyHostArgumentsRemainUnchanged() {
        String[][] cases = {
                {"--quickPlayMultiplayer", "play.example.invalid:25570", "--test-path", "/private files/test"},
                {"--test-path", "/private files/test", "--test-flag"}
        };
        for (String[] host : cases) {
            for (Object[] metadata : new Object[][] {null, modernArguments()}) {
                List<String> arguments = baseArguments();
                List<String> expected = baseArguments();
                expected.addAll(Arrays.asList(host));
                HostGameArguments.appendTo(arguments, host, metadata);
                assertEquals(expected, arguments);
            }
        }
    }

    @Test
    public void omittedPortKeepsTheLegacyDefaultWithoutRequiringANewHostContract() {
        String[] host = {"--server", "play.example.invalid"};
        List<String> legacy = new ArrayList<>();
        HostGameArguments.appendTo(legacy, host, null);
        assertEquals(Arrays.asList(host), legacy);

        List<String> modern = new ArrayList<>();
        HostGameArguments.appendTo(modern, host, modernArguments());
        assertEquals(Arrays.asList("--quickPlayMultiplayer", "play.example.invalid:25565"), modern);
    }

    @Test
    public void incompleteDuplicateOrMixedConnectionArgumentsAreRejectedBeforeAppending() {
        String[][] invalid = {
                {"--server"},
                {"--port", "25565"},
                {"--server", "", "--port", "25565"},
                {"--server", null, "--port", "25565"},
                {"--server", "--port", "25565"},
                {"--server", "play.example.invalid", "--port", "25565", "--server", "another.example.invalid"},
                {"--server", "play.example.invalid", "--port", "25565", "--port", "25570"},
                {"--quickPlayMultiplayer"},
                {"--quickPlayMultiplayer", "play.example.invalid", "--quickPlayMultiplayer", "another.example.invalid"},
                {"--server", "play.example.invalid", "--port", "25565", "--quickPlayMultiplayer", "another.example.invalid"}
        };
        for (String[] host : invalid) {
            assertRejectedWithoutPartialAppend(host);
        }
    }

    @Test
    public void invalidEndpointSyntaxAndPortsAreRejectedWithoutDnsLookup() {
        for (String port : new String[] {"0", "65536", "99999999999999999", "-1", "+25565", "25.5", " 25565"}) {
            assertRejectedWithoutPartialAppend(connection("play.example.invalid", port));
        }
        String[] invalidHosts = {
                "bad host",
                "play.example.invalid/path",
                "https://play.example.invalid",
                "play.example.invalid:25565",
                "user@play.example.invalid",
                "play.example.invalid?query",
                "play.example.invalid#fragment",
                "[broken]"
        };
        for (String host : invalidHosts) {
            assertRejectedWithoutPartialAppend(connection(host, "25565"));
        }
    }

    private static void assertRejectedWithoutPartialAppend(String[] host) {
        for (Object[] metadata : new Object[][] {null, modernArguments()}) {
            List<String> arguments = baseArguments();
            assertThrows(
                    Arrays.toString(host),
                    IllegalArgumentException.class,
                    () -> HostGameArguments.appendTo(arguments, host, metadata)
            );
            assertEquals(baseArguments(), arguments);
        }
    }

    private static List<String> baseArguments() {
        return new ArrayList<>(Arrays.asList("--username", "TestPlayer"));
    }

    private static String[] connection(String host, String port) {
        return new String[] {"--server", host, "--port", port};
    }

    private static Object[] modernArguments() {
        // 부모 게임의 feature 인수와 로더 인수가 병합된 Gson 구조를 그대로 사용한다.
        String json = "{\"id\":\"TestVersion\",\"arguments\":{\"game\":["
                + "\"--username\",\"${auth_player_name}\","
                + "{\"rules\":[{\"action\":\"allow\",\"features\":{\"is_quick_play_multiplayer\":true}}],"
                + "\"value\":[\"--quickPlayMultiplayer\",\"${quickPlayMultiplayer}\"]},"
                + "\"--loader-option\",\"test-value\"]}}";
        JMinecraftVersionList.Version version = new Gson().fromJson(json, JMinecraftVersionList.Version.class);
        return version.arguments.game;
    }
}
