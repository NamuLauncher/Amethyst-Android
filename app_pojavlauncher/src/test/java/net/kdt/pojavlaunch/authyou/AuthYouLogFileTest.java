package net.kdt.pojavlaunch.authyou;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AuthYouLogFileTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void eachEventIsWrittenAsOneLineAndExistingContentIsCleared() throws Exception {
        File file = new File(temporary.newFolder("TestRun"), "latest.log");
        Files.write(file.toPath(), "previous run\n".getBytes(StandardCharsets.UTF_8));

        AuthYouLogFile listener = AuthYouLogFile.open(file);
        listener.onEventLogged("Connec");
        listener.onEventLogged("ting to example.test");
        listener.onEventLogged("[AY3:CLIENT] Game exited 0");

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertEquals("Connec\nting to example.test\n[AY3:CLIENT] Game exited 0\n", content);
    }

    @Test
    public void missingParentDirectoryIsCreated() throws Exception {
        File file = new File(temporary.getRoot(), "TestInstance/nested/latest.log");

        AuthYouLogFile.open(file).onEventLogged("테스트 서버");

        assertEquals("테스트 서버\n", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
}
