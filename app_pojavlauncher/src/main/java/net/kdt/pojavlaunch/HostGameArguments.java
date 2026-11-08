package net.kdt.pojavlaunch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 호스트의 접속 인수를 실제 게임 메타데이터가 지원하는 형태로 맞춘다. */
final class HostGameArguments {
    private static final String SERVER = "--server";
    private static final String PORT = "--port";
    private static final String QUICK_PLAY_MULTIPLAYER = "--quickPlayMultiplayer";

    private HostGameArguments() {}

    static void appendTo(
            List<String> arguments,
            String[] hostArguments,
            Object[] versionArguments
    ) {
        if (hostArguments == null || hostArguments.length == 0) {
            return;
        }

        int serverIndex = -1;
        int portIndex = -1;
        int quickPlayIndex = -1;
        for (int index = 0; index < hostArguments.length; index++) {
            String argument = hostArguments[index];
            if (SERVER.equals(argument)) {
                requireUnique(serverIndex, SERVER);
                requireValue(hostArguments, index);
                serverIndex = index;
                index++;
            } else if (PORT.equals(argument)) {
                requireUnique(portIndex, PORT);
                requireValue(hostArguments, index);
                portIndex = index;
                index++;
            } else if (QUICK_PLAY_MULTIPLAYER.equals(argument)) {
                requireUnique(quickPlayIndex, QUICK_PLAY_MULTIPLAYER);
                requireValue(hostArguments, index);
                quickPlayIndex = index;
                index++;
            }
        }

        if (serverIndex < 0 && portIndex < 0) {
            arguments.addAll(Arrays.asList(hostArguments));
            return;
        }
        if (serverIndex < 0 || quickPlayIndex >= 0) {
            throw new IllegalArgumentException("호스트 접속 인수는 server 또는 quickPlayMultiplayer로 지정해야 합니다");
        }

        String port = "25565";
        if (portIndex >= 0) {
            port = hostArguments[portIndex + 1];
        }
        String endpoint = formatEndpoint(hostArguments[serverIndex + 1], port);
        if (!supportsQuickPlay(versionArguments)) {
            arguments.addAll(Arrays.asList(hostArguments));
            return;
        }

        // 모든 입력을 확인한 뒤 결합하므로 잘못된 접속 인수가 부분 argv를 남기지 않는다.
        for (int index = 0; index < hostArguments.length; index++) {
            if (index == serverIndex) {
                arguments.add(QUICK_PLAY_MULTIPLAYER);
                arguments.add(endpoint);
                index++;
            } else if (index == portIndex) {
                index++;
            } else {
                arguments.add(hostArguments[index]);
            }
        }
    }

    private static boolean supportsQuickPlay(Object[] versionArguments) {
        if (versionArguments == null) {
            return false;
        }
        for (Object argument : versionArguments) {
            if (QUICK_PLAY_MULTIPLAYER.equals(argument)) {
                return true;
            }
            if (!(argument instanceof Map)) {
                continue;
            }
            Object value = ((Map<?, ?>) argument).get("value");
            if (QUICK_PLAY_MULTIPLAYER.equals(value)) {
                return true;
            }
            if (value instanceof List && ((List<?>) value).contains(QUICK_PLAY_MULTIPLAYER)) {
                return true;
            }
        }
        return false;
    }

    private static void requireUnique(int previousIndex, String option) {
        if (previousIndex >= 0) {
            throw new IllegalArgumentException("호스트 접속 인수가 중복되었습니다: " + option);
        }
    }

    private static void requireValue(String[] arguments, int index) {
        if (index + 1 >= arguments.length
                || arguments[index + 1] == null
                || arguments[index + 1].isEmpty()
                || arguments[index + 1].startsWith("--")) {
            throw new IllegalArgumentException("호스트 접속 인수의 값이 없습니다: " + arguments[index]);
        }
    }

    private static String formatEndpoint(String host, String portValue) {
        if (!portValue.matches("[0-9]+")) {
            throw new IllegalArgumentException("서버 포트는 1부터 65535까지의 정수여야 합니다");
        }
        int port;
        try {
            port = Integer.parseInt(portValue);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("서버 포트는 1부터 65535까지의 정수여야 합니다", failure);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("서버 포트는 1부터 65535까지의 정수여야 합니다");
        }

        try {
            // URI의 host 생성자는 DNS 조회 없이 IPv6 괄호와 주소 구문을 처리한다.
            URI endpoint = new URI(
                    null,
                    null,
                    host,
                    port,
                    null,
                    null,
                    null
            );
            if (endpoint.getHost() == null
                    || endpoint.getPort() != port
                    || endpoint.getRawUserInfo() != null
                    || !"".equals(endpoint.getRawPath())
                    || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null) {
                throw new IllegalArgumentException("서버 호스트 이름이 올바르지 않습니다");
            }
            return endpoint.getRawAuthority();
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("서버 호스트 이름이 올바르지 않습니다", failure);
        }
    }
}
