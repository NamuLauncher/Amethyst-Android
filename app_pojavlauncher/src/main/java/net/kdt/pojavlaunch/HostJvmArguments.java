package net.kdt.pojavlaunch;

import java.util.List;

/** 런타임 기본 인수 뒤에 호스트 인수를 추가할 때 로깅 설정의 우선순위를 정한다. */
final class HostJvmArguments {
    private static final String LOGGING_PROPERTY = "-Dlog4j.configurationFile";

    private HostJvmArguments() {}

    static void appendTo(List<String> arguments, String[] hostArguments) {
        if (hostArguments == null || hostArguments.length == 0) {
            return;
        }

        int lastLoggingIndex = -1;
        for (int index = 0; index < hostArguments.length; index++) {
            if (isLoggingProperty(hostArguments[index])) {
                lastLoggingIndex = index;
            }
        }

        if (lastLoggingIndex >= 0) {
            // 호스트가 명시한 설정만 기본 보안 XML 선택을 대체한다.
            arguments.removeIf(HostJvmArguments::isLoggingProperty);
        }

        for (int index = 0; index < hostArguments.length; index++) {
            String argument = hostArguments[index];
            if (isLoggingProperty(argument) && index != lastLoggingIndex) {
                continue;
            }
            arguments.add(argument);
        }
    }

    private static boolean isLoggingProperty(String argument) {
        return LOGGING_PROPERTY.equals(argument)
                || (argument != null && argument.startsWith(LOGGING_PROPERTY + "="));
    }
}
