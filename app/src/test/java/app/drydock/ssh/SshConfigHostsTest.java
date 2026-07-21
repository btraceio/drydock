package app.drydock.ssh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SshConfigHostsTest {

    @Test
    void parsesAliasesSkipsPatternsAndMatchBlocks() {
        String config = """
                # comment
                Host build
                  HostName build.example.com
                host prod prod-db
                Host *
                  ServerAliveInterval 30
                Host !secret staging
                Match user deploy
                  IdentityFile ~/.ssh/deploy
                HOST=quoted
                Host "with space"
                Include ~/.ssh/config.d/*
                """;
        assertEquals(List.of("build", "prod", "prod-db", "staging", "quoted", "with space"),
                SshConfigHosts.parse(config));
    }

    @Test
    void emptyAndMalformedInputYieldsEmpty() {
        assertEquals(List.of(), SshConfigHosts.parse(""));
        assertEquals(List.of(), SshConfigHosts.parse("garbage without host keyword\n===\n"));
    }

    @Test
    void deduplicates() {
        assertEquals(List.of("a"), SshConfigHosts.parse("Host a\nHost a\n"));
    }
}
