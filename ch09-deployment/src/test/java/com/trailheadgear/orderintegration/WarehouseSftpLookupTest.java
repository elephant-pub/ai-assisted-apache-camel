package com.trailheadgear.orderintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.Test;

/**
 * The inbound-then-processed lookup of WarehouseSftp (design 005 T4, F5) and its upload (design 006 A-9, C-014, P-4),
 * on a recording fake: no SFTP server.
 */
class WarehouseSftpLookupTest {

    private static final String FILE = "PL-TG-1001.csv";

    private final WarehouseSftp sftp = new WarehouseSftp("unused", 22, "u", "p", "unused", 1000, "inbound-dir", "processed-dir");
    private final List<String> lookups = new ArrayList<>();
    /** Every call on the fake, in order: {@code exists <path>}, {@code put <path> <n> bytes}, {@code rename <a> <b>}. */
    private final List<String> calls = new ArrayList<>();

    /** A fake server holding {@code existing}; {@code failure}, when set, is thrown by every lookup. */
    private final class Fake implements WarehouseSftp.RemoteFiles {
        private final Set<String> existing;
        private final SftpException failure;

        Fake(Set<String> existing, SftpException failure) {
            this.existing = existing;
            this.failure = failure;
        }

        @Override
        public boolean exists(String path) throws SftpException {
            lookups.add(path);
            calls.add("exists " + path);
            if (failure != null) {
                throw failure;
            }
            return existing.contains(path);
        }

        @Override
        public void put(String tmpPath, byte[] bytes) {
            calls.add("put " + tmpPath + " " + bytes.length + " bytes");
        }

        @Override
        public void rename(String from, String to) {
            calls.add("rename " + from + " " + to);
        }
    }

    private WarehouseSftp.RemoteFiles fake(Set<String> existing) {
        return new Fake(existing, null);
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
        WarehouseSftp.RemoteFiles failing = new Fake(Set.of(), failure);

        assertThatThrownBy(() -> sftp.deliveredFolder(FILE, failing)).isSameAs(failure);
    }

    @Test
    void anInvalidFileNameIsRejectedWithoutALookup() {
        WarehouseSftp.RemoteFiles files = fake(Set.of());

        assertThatThrownBy(() -> sftp.deliveredFolder("../PL-TG-1001.csv", files)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sftp.deliveredFolder(null, files)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lookups).isEmpty();
    }

    // Design 006 D16 (F-2): the folders resolve as Camel's sftp producer resolves the endpoint path

    @Test
    void absoluteFoldersAreLookedUpRelativeToTheLoginDirectory() throws Exception {
        WarehouseSftp absolute = new WarehouseSftp("unused", 22, "u", "p", "unused", 1000, "/inbound/picklists",
            "/inbound/picklists/processed");

        assertThat(absolute.deliveredFolder(FILE, fake(Set.of()))).isNull();
        assertThat(lookups).containsExactly("inbound/picklists/" + FILE, "inbound/picklists/processed/" + FILE);
    }

    @Test
    void remoteFolderDropsEveryLeadingSlashAsTheSftpProducerDoes() {
        // The producer logs: "/inbound/picklists" will be converted to "inbound/picklists"
        assertThat(WarehouseSftp.remoteFolder("/inbound/picklists")).isEqualTo("inbound/picklists");
        assertThat(WarehouseSftp.remoteFolder("inbound/picklists")).isEqualTo("inbound/picklists");
        assertThat(WarehouseSftp.remoteFolder("//a/b")).isEqualTo("a/b");
    }

    // Design 006 A-9 (P-4): the upload keeps C-014 and the old endpoint's guards

    @Test
    void uploadChecksTheFinalNameThenWritesTheTemporaryNameThenRenames() throws Exception {
        WarehouseSftp absolute = new WarehouseSftp("unused", 22, "u", "p", "unused", 1000, "/inbound",
            "/inbound/processed");
        byte[] bytes = "order_no\r\nTG-1001\r\n".getBytes(StandardCharsets.UTF_8);

        absolute.upload(FILE, bytes, fake(Set.of()));

        assertThat(calls).containsExactly(
            "exists inbound/" + FILE,
            "put inbound/" + FILE + ".tmp " + bytes.length + " bytes",
            "rename inbound/" + FILE + ".tmp inbound/" + FILE);
    }

    @Test
    void aFinalFileAlreadyThereFailsTheUploadWithoutAWrite() {
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> sftp.upload(FILE, bytes, fake(Set.of("inbound-dir/" + FILE))))
            .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(calls).containsExactly("exists inbound-dir/" + FILE);
    }

    @Test
    void anInvalidFileNameIsRejectedWithoutAnUploadCall() {
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        WarehouseSftp.RemoteFiles files = fake(Set.of());

        assertThatThrownBy(() -> sftp.upload("../PL-TG-1001.csv", bytes, files)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sftp.upload(null, bytes, files)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
}
