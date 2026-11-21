package com.trailheadgear.orderintegration.spec.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The identity of the Warehouse SFTP Server for one test run (design 004 §3.3, §4 Security, §7).
 *
 * <p>Generates an Ed25519 and an RSA 3072 host key with JSch {@link KeyPair} into a directory under
 * {@code spec-test.work-dir} ({@code target/spec-tests/sftp/}). The private keys are written in the OpenSSH v1 private
 * key format ({@code -----BEGIN OPENSSH PRIVATE KEY-----}, unencrypted), the native format of {@code ssh-keygen} that
 * OpenSSH's sshd reads for both key types without depending on OpenSSL PEM support. The files are owner-only
 * ({@code rw-------}), as sshd requires for a {@code HostKey}. The key material comes from JSch's agent encoding
 * ({@link KeyPair#forSSHAgent()}); it is parsed and checked against the public key blob before it is written, so a
 * change in that encoding fails the run instead of producing a key sshd would reject.
 *
 * <p>If this Java cannot generate Ed25519 keys, the run continues with the RSA key only; {@link #hasEd25519()} tells,
 * and it is logged (design §7, "for implementation to confirm").
 *
 * <p>{@link #writeKnownHosts(int, Path)} writes {@code [127.0.0.1]:<port> <type> <base64>} for every key. The
 * integration keeps {@code strictHostKeyChecking=yes}; nothing here relaxes it.
 */
public final class WarehouseHostKeys {

    private static final Logger LOG = LoggerFactory.getLogger(WarehouseHostKeys.class);

    public static final String ED25519_KEY_FILE = "ssh_host_ed25519_key";
    public static final String RSA_KEY_FILE = "ssh_host_rsa_key";
    private static final String ED25519_TYPE = "ssh-ed25519";
    private static final String RSA_TYPE = "ssh-rsa";
    private static final int RSA_BITS = 3072;
    private static final String COMMENT = "spec-test-warehouse-host-key";
    private static final Set<PosixFilePermission> OWNER_ONLY =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path ed25519PrivateKey;
    private final Path rsaPrivateKey;
    private final byte[] ed25519PublicBlob;
    private final byte[] rsaPublicBlob;

    private WarehouseHostKeys(Path ed25519PrivateKey, byte[] ed25519PublicBlob, Path rsaPrivateKey, byte[] rsaPublicBlob) {
        this.ed25519PrivateKey = ed25519PrivateKey;
        this.ed25519PublicBlob = ed25519PublicBlob;
        this.rsaPrivateKey = rsaPrivateKey;
        this.rsaPublicBlob = rsaPublicBlob;
    }

    /**
     * Generates the host keys into {@code dir}, which must lie inside {@link SpecRun#workDir()}. Existing key files of
     * an earlier run are replaced.
     */
    public static WarehouseHostKeys generate(Path dir) throws IOException {
        Path target = dir.toAbsolutePath().normalize();
        Path workDir = SpecRun.workDir().normalize();
        if (!target.startsWith(workDir)) {
            throw new IllegalArgumentException("Host keys are written only under " + workDir + ", not to " + target);
        }
        Files.createDirectories(target);
        JSch jsch = new JSch();

        Path ed25519Path = null;
        byte[] ed25519Blob = null;
        KeyPair ed25519 = null;
        try {
            ed25519 = KeyPair.genKeyPair(jsch, KeyPair.ED25519);
        } catch (JSchException | RuntimeException | LinkageError e) {
            LOG.warn("Ed25519 host key generation is not available on this Java ({}); the Warehouse SFTP Server uses "
                + "the RSA host key only", e.toString());
        }
        if (ed25519 != null) {
            try {
                ed25519Blob = ed25519.getPublicKeyBlob();
                ed25519Path = target.resolve(ED25519_KEY_FILE);
                writeKey(ed25519Path, ed25519Blob, ed25519PrivateSection(ed25519, ed25519Blob));
            } finally {
                ed25519.dispose();
            }
            LOG.info("Ed25519 host key generated: {}", ed25519Path);
        }

        KeyPair rsa;
        try {
            rsa = KeyPair.genKeyPair(jsch, KeyPair.RSA, RSA_BITS);
        } catch (JSchException e) {
            throw new IOException("RSA host key generation failed", e);
        }
        Path rsaPath = target.resolve(RSA_KEY_FILE);
        byte[] rsaBlob;
        try {
            rsaBlob = rsa.getPublicKeyBlob();
            writeKey(rsaPath, rsaBlob, rsaPrivateSection(rsa, rsaBlob));
        } finally {
            rsa.dispose();
        }
        LOG.info("RSA {} host key generated: {}", RSA_BITS, rsaPath);

        return new WarehouseHostKeys(ed25519Path, ed25519Blob, rsaPath, rsaBlob);
    }

    /** True when the Ed25519 host key exists; false when this Java could only generate the RSA key. */
    public boolean hasEd25519() {
        return ed25519PrivateKey != null;
    }

    /** The Ed25519 private key file ({@code ssh_host_ed25519_key}), empty when {@link #hasEd25519()} is false. */
    public Optional<Path> ed25519PrivateKey() {
        return Optional.ofNullable(ed25519PrivateKey);
    }

    /** The RSA private key file ({@code ssh_host_rsa_key}). */
    public Path rsaPrivateKey() {
        return rsaPrivateKey;
    }

    /** All private key files, Ed25519 first when present. */
    public List<Path> privateKeys() {
        List<Path> keys = new ArrayList<>();
        if (ed25519PrivateKey != null) {
            keys.add(ed25519PrivateKey);
        }
        keys.add(rsaPrivateKey);
        return List.copyOf(keys);
    }

    /**
     * Writes a known_hosts file for the server on {@code 127.0.0.1:<port>}: one line {@code [127.0.0.1]:<port> <type>
     * <base64>} per host key.
     */
    public void writeKnownHosts(int port, Path file) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Not a port: " + port);
        }
        String host = "[127.0.0.1]:" + port;
        StringBuilder lines = new StringBuilder();
        if (ed25519PublicBlob != null) {
            lines.append(host).append(' ').append(ED25519_TYPE).append(' ')
                .append(Base64.getEncoder().encodeToString(ed25519PublicBlob)).append('\n');
        }
        lines.append(host).append(' ').append(RSA_TYPE).append(' ')
            .append(Base64.getEncoder().encodeToString(rsaPublicBlob)).append('\n');
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, lines.toString(), StandardCharsets.US_ASCII);
    }

    // --- OpenSSH key encoding ---

    /** Key type and fields of the Ed25519 private section: string type, string pk(32), string sk||pk(64). */
    private static byte[] ed25519PrivateSection(KeyPair key, byte[] publicBlob) throws IOException {
        SshReader pub = new SshReader(publicBlob);
        requireType(pub.string(), ED25519_TYPE);
        byte[] pk = pub.string();
        if (pk.length != 32) {
            throw new IOException("Unexpected Ed25519 public key length " + pk.length);
        }
        SshReader agent = new SshReader(agentEncoding(key));
        requireType(agent.string(), ED25519_TYPE);
        byte[] agentPk = agent.string();
        byte[] sk = agent.string();
        if (!Arrays.equals(agentPk, pk)) {
            throw new IOException("JSch Ed25519 agent encoding does not match its public key");
        }
        byte[] skPk;
        if (sk.length == 64) {
            if (!Arrays.equals(Arrays.copyOfRange(sk, 32, 64), pk)) {
                throw new IOException("JSch Ed25519 private key does not end with its public key");
            }
            skPk = sk;
        } else if (sk.length == 32) {
            skPk = concat(sk, pk);
        } else {
            throw new IOException("Unexpected Ed25519 private key length " + sk.length);
        }
        SshWriter out = new SshWriter();
        out.string(ED25519_TYPE.getBytes(StandardCharsets.US_ASCII));
        out.string(pk);
        out.string(skPk);
        Arrays.fill(skPk, (byte) 0);
        return out.toByteArray();
    }

    /** Key type and fields of the RSA private section: string type, mpint n, e, d, iqmp, p, q. */
    private static byte[] rsaPrivateSection(KeyPair key, byte[] publicBlob) throws IOException {
        SshReader pub = new SshReader(publicBlob);
        requireType(pub.string(), RSA_TYPE);
        BigInteger e = new BigInteger(pub.string());
        BigInteger n = new BigInteger(pub.string());

        SshReader agent = new SshReader(agentEncoding(key));
        requireType(agent.string(), RSA_TYPE);
        BigInteger agentN = new BigInteger(agent.string());
        BigInteger agentE = new BigInteger(agent.string());
        BigInteger d = new BigInteger(agent.string());
        agent.string(); // iqmp as JSch encodes it; recomputed below from p and q
        BigInteger p = new BigInteger(agent.string());
        BigInteger q = new BigInteger(agent.string());
        if (!agentN.equals(n) || !agentE.equals(e) || !p.multiply(q).equals(n)) {
            throw new IOException("JSch RSA agent encoding does not match its public key");
        }
        BigInteger probe = BigInteger.TWO;
        if (!probe.modPow(e, n).modPow(d, n).equals(probe)) {
            throw new IOException("JSch RSA private exponent does not match its public key");
        }
        BigInteger iqmp = q.modInverse(p);
        SshWriter out = new SshWriter();
        out.string(RSA_TYPE.getBytes(StandardCharsets.US_ASCII));
        out.mpint(n);
        out.mpint(e);
        out.mpint(d);
        out.mpint(iqmp);
        out.mpint(p);
        out.mpint(q);
        return out.toByteArray();
    }

    private static byte[] agentEncoding(KeyPair key) throws IOException {
        try {
            return key.forSSHAgent();
        } catch (JSchException e) {
            throw new IOException("JSch could not encode the private key", e);
        }
    }

    private static void requireType(byte[] type, String expected) throws IOException {
        String actual = new String(type, StandardCharsets.US_ASCII);
        if (!actual.equals(expected)) {
            throw new IOException("Unexpected key type " + actual + ", expected " + expected);
        }
    }

    /** Writes {@code <file>} (OpenSSH v1 private key, owner-only) and {@code <file>.pub}. */
    private static void writeKey(Path file, byte[] publicBlob, byte[] keyFields) throws IOException {
        int check = new SecureRandom().nextInt();
        SshWriter section = new SshWriter();
        section.uint32(check);
        section.uint32(check);
        section.raw(keyFields);
        section.string(COMMENT.getBytes(StandardCharsets.US_ASCII));
        // Cipher "none" has block size 8: pad with 1, 2, 3, ...
        for (int pad = 1; section.size() % 8 != 0; pad++) {
            section.raw(new byte[] {(byte) pad});
        }
        byte[] privateSection = section.toByteArray();

        SshWriter blob = new SshWriter();
        blob.raw("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
        blob.string("none".getBytes(StandardCharsets.US_ASCII));
        blob.string("none".getBytes(StandardCharsets.US_ASCII));
        blob.string(new byte[0]);
        blob.uint32(1);
        blob.string(publicBlob);
        blob.string(privateSection);
        Arrays.fill(privateSection, (byte) 0);
        byte[] encoded = blob.toByteArray();

        String pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(70, new byte[] {'\n'}).encodeToString(encoded)
            + "\n-----END OPENSSH PRIVATE KEY-----\n";
        Arrays.fill(encoded, (byte) 0);

        Files.deleteIfExists(file);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        Files.writeString(file, pem, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING);
        if (!Files.getPosixFilePermissions(file).equals(OWNER_ONLY)) {
            throw new IOException("Host key " + file + " is not owner-only");
        }

        String type = new String(new SshReader(publicBlob).string(), StandardCharsets.US_ASCII);
        Files.writeString(file.resolveSibling(file.getFileName() + ".pub"),
            type + " " + Base64.getEncoder().encodeToString(publicBlob) + " " + COMMENT + "\n",
            StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /** Reads SSH wire format (RFC 4251 §5) strings. */
    private static final class SshReader {
        private final ByteBuffer buffer;

        SshReader(byte[] data) {
            this.buffer = ByteBuffer.wrap(data);
        }

        byte[] string() throws IOException {
            if (buffer.remaining() < 4) {
                throw new IOException("Truncated SSH key encoding");
            }
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                throw new IOException("Invalid SSH string length " + length);
            }
            byte[] value = new byte[length];
            buffer.get(value);
            return value;
        }
    }

    /** Writes SSH wire format (RFC 4251 §5). */
    private static final class SshWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void uint32(int value) {
            out.write(value >>> 24);
            out.write(value >>> 16);
            out.write(value >>> 8);
            out.write(value);
        }

        void string(byte[] value) {
            uint32(value.length);
            out.writeBytes(value);
        }

        void mpint(BigInteger value) {
            // Two's complement, minimal, with a leading zero byte when the high bit is set; zero is the empty string
            string(value.signum() == 0 ? new byte[0] : value.toByteArray());
        }

        void raw(byte[] value) {
            out.writeBytes(value);
        }

        int size() {
            return out.size();
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
