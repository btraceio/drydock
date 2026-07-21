package app.drydock.ui;

import app.drydock.git.SshUnreachableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteRepositoryModalTest {

    @Test
    void hostKeyFailureGetsActionableMessage() {
        String message = RemoteRepositoryModal.userMessage(
                new SshUnreachableException("h", "Host key verification failed."));
        assertTrue(message.contains("ssh h"));
    }

    @Test
    void authFailureMentionsAgent() {
        String message = RemoteRepositoryModal.userMessage(
                new SshUnreachableException("h", "user@h: Permission denied (publickey)."));
        assertTrue(message.contains("ssh-agent"));
    }
}
