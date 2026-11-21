package com.trailheadgear.orderintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.Test;

/** The inbound-then-processed lookup of WarehouseSftp (design 005 T4, F5), on a recording fake: no SFTP server. */
class WarehouseSftpLookupTest {

    private static final String FILE = "PL-TG-1001.csv";

    private final WarehouseSftp sftp = new WarehouseSftp("unused", 22, "u", "p", "unused", 1000, "inbound-dir", "processed-dir");
    private final List<String> lookups = new ArrayList<>();

    private WarehouseSftp.RemoteFiles fake(Set<String> existing) {
        return path -> {
            lookups.add(path);
            return existing.contains(path);
        };
    }

    @Test
    void inInboundIsInboundAndOnlyInboundIsLookedUp() throws Exception {
        assertThat(sftp.deliveredFolder(FILE, fake(Set.of("inbound-dir/" + FILE)))).isEqualTo(WarehouseSftp.INBOUND);
        assertThat(lookups).containsExactly("inbound-dir/" + FILE);
    }

    @Test
    void inProcessedIsProcessedAfterInboundWasLookedUp() throws Exception {
        assertThat(sftp.deliveredFolder(FILE, fake(Set.of("processed-dir/" + FILE)))).isEqualTo(WarehouseSftp.PROCESSED);
        assertThat(lookups).containsExactly("inbound-dir/" + FILE, "processed-dir/" + FILE);
    }

    @Test
    void inNeitherIsNullAfterBothLookups() throws Exception {
        assertThat(sftp.deliveredFolder(FILE, fake(Set.of()))).isNull();
        assertThat(lookups).containsExactly("inbound-dir/" + FILE, "processed-dir/" + FILE);
    }

    @Test
    void aFailureOfTheLookupIsThrown() {
        SftpException failure = new SftpException(4, "failure");
        WarehouseSftp.RemoteFiles failing = path -> {
            throw failure;
        };

        assertThatThrownBy(() -> sftp.deliveredFolder(FILE, failing)).isSameAs(failure);
    }

    @Test
    void anInvalidFileNameIsRejectedWithoutALookup() {
        WarehouseSftp.RemoteFiles files = fake(Set.of());

        assertThatThrownBy(() -> sftp.deliveredFolder("../PL-TG-1001.csv", files)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sftp.deliveredFolder(null, files)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lookups).isEmpty();
    }
}
