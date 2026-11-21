package com.trailheadgear.orderintegration.spec.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * The Warehouse Team's view of the Warehouse SFTP Server (design 004 §3.3, A-4, Task 11): plain JSch (from
 * {@code camel-ftp-starter}) and commons-csv (from {@code camel-csv-starter}).
 *
 * <p>Every call opens its own session and channel and closes both before it returns. Host-key checking is strict
 * ({@code StrictHostKeyChecking=yes}) against a known_hosts file only: by default the environment's, for TC-005 the one
 * of its own server ({@link #WarehouseInbox(String, int, Path)}). A failed connect throws an {@link IllegalStateException}
 * naming host, port and known_hosts file (never the password).
 *
 * <p>The folders are {@value #INBOUND} and {@value #PROCESSED}, relative to the login directory, as the integration's
 * spec-test profile configures them. A Pick List counts wherever it is: the Warehouse moves picked-up files to the
 * processed folder. The integration writes {@code <file>.tmp} in the inbound folder and renames it when complete;
 * {@link #tmpExists(String)} looks in both folders.
 *
 * <p><b>Comparison.</b> {@link #assertEqualsExample(String, Path)} parses the file as RFC 4180 (the format the route
 * marshals: comma, minimal quoting, CRLF) and compares it with the example JSON array field by field: the header must be
 * exactly the twelve columns of {@code docs/inputs/warehouse-picklist-format.md} in that order, every example object
 * must have exactly these keys in this order, the number of rows must match, every row must have twelve fields, and
 * each field must equal the example value as text (a JSON string as is, a JSON integer in its decimal form). Nothing is
 * trimmed or normalised.
 */
public final class WarehouseInbox {

    public static final String INBOUND = "inbound/picklists";
    public static final String PROCESSED = "inbound/picklists/processed";
    public static final String TMP_SUFFIX = ".tmp";

    /** Column order of {@code docs/inputs/warehouse-picklist-format.md} and of the route's CSV header. */
    public static final List<String> COLUMNS = List.of("order_no", "line_no", "sku", "description", "qty",
        "unit_weight_g", "ship_name", "ship_street", "ship_city", "ship_postcode", "ship_country", "total_weight_g");

    private static final List<String> FOLDERS = List.of(INBOUND, PROCESSED);
    private static final Pattern FILE_NAME = Pattern.compile("^PL-[A-Z]{2}-[0-9]+\\.csv$");
    private static final int TIMEOUT_MS = 10_000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final Path knownHostsFile;

    /** The environment's Warehouse SFTP Server. */
    public WarehouseInbox() {
        this(SpecEnvironment.get().sftpHost(), SpecEnvironment.get().sftpPort(), SpecEnvironment.get().knownHostsFile());
    }

    /** Another server with the run's user and password (TC-005), checked against {@code knownHostsFile}. */
    public WarehouseInbox(String host, int port, Path knownHostsFile) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Not a port: " + port);
        }
        if (knownHostsFile == null) {
            throw new IllegalArgumentException("knownHostsFile is required");
        }
        SpecEnvironment env = SpecEnvironment.get();
        this.host = host;
        this.port = port;
        this.username = env.sftpUsername();
        this.password = env.sftpPassword();
        this.knownHostsFile = knownHostsFile.toAbsolutePath().normalize();
    }

    /** How many of the two folders hold {@code fileName} (0, 1 or 2). */
    public int count(String fileName) {
        String name = requireFileName(fileName);
        return withSftp(sftp -> {
            int count = 0;
            for (String folder : FOLDERS) {
                if (exists(sftp, folder + "/" + name)) {
                    count++;
                }
            }
            return count;
        });
    }

    /** Whether {@code fileName.tmp} exists in either folder. */
    public boolean tmpExists(String fileName) {
        String tmp = requireFileName(fileName) + TMP_SUFFIX;
        return withSftp(sftp -> {
            for (String folder : FOLDERS) {
                if (exists(sftp, folder + "/" + tmp)) {
                    return true;
                }
            }
            return false;
        });
    }

    /** The file's content (UTF-8) from the inbound folder, or else the processed folder; fails when in neither. */
    public String read(String fileName) {
        String name = requireFileName(fileName);
        byte[] bytes = withSftp(sftp -> {
            for (String folder : FOLDERS) {
                String path = folder + "/" + name;
                if (exists(sftp, path)) {
                    try (InputStream in = sftp.get(path)) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        in.transferTo(out);
                        return out.toByteArray();
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read " + path, e);
                    } catch (SftpException e) {
                        throw new IllegalStateException("Cannot read " + path + ": " + e.getMessage(), e);
                    }
                }
            }
            throw new AssertionError(name + " is in neither " + INBOUND + " nor " + PROCESSED);
        });
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new AssertionError(name + " is not valid UTF-8", e);
        }
    }

    /** All records of an RFC 4180 CSV text, the header record first. */
    public static List<List<String>> parse(String csv) {
        List<List<String>> records = new ArrayList<>();
        try (CSVParser parser = CSVFormat.RFC4180.parse(new StringReader(csv))) {
            for (CSVRecord record : parser) {
                records.add(record.toList());
            }
        } catch (IOException e) {
            throw new AssertionError("The pick list is not RFC 4180 CSV: " + e.getMessage(), e);
        }
        return records;
    }

    /** Reads {@code fileName} and compares header and rows field by field with the example JSON array. */
    public void assertEqualsExample(String fileName, Path exampleJson) {
        List<List<String>> expected = expectedRecords(exampleJson);
        List<List<String>> actual = parse(read(fileName));
        if (actual.isEmpty()) {
            throw new AssertionError(fileName + " is empty");
        }
        if (!COLUMNS.equals(actual.get(0))) {
            throw new AssertionError(fileName + ": header " + actual.get(0) + ", expected " + COLUMNS);
        }
        int rows = actual.size() - 1;
        if (rows != expected.size()) {
            throw new AssertionError(fileName + ": " + rows + " rows, expected " + expected.size() + " (" + exampleJson
                + ")");
        }
        for (int r = 0; r < rows; r++) {
            List<String> row = actual.get(r + 1);
            if (row.size() != COLUMNS.size()) {
                throw new AssertionError(fileName + " row " + (r + 1) + ": " + row.size() + " fields, expected "
                    + COLUMNS.size());
            }
            for (int c = 0; c < COLUMNS.size(); c++) {
                String want = expected.get(r).get(c);
                String got = row.get(c);
                if (!want.equals(got)) {
                    throw new AssertionError(fileName + " row " + (r + 1) + " column " + COLUMNS.get(c) + ": '" + got
                        + "', expected '" + want + "' (" + exampleJson.getFileName() + ")");
                }
            }
        }
    }

    /** Whether {@code fileName} is in the processed folder. */
    public boolean inProcessed(String fileName) {
        String name = requireFileName(fileName);
        return withSftp(sftp -> exists(sftp, PROCESSED + "/" + name));
    }

    /**
     * Design 005 §3.9 (KillIT K2): moves {@code fileName} from the inbound to the processed folder with an SFTP rename,
     * as the Warehouse does with a picked-up file; fails when it is not in the inbound folder.
     */
    public void moveToProcessed(String fileName) {
        String name = requireFileName(fileName);
        withSftp(sftp -> {
            try {
                sftp.rename(INBOUND + "/" + name, PROCESSED + "/" + name);
            } catch (SftpException e) {
                throw new IllegalStateException("Cannot move " + name + " to " + PROCESSED + ": " + e.getMessage(), e);
            }
            return null;
        });
    }

    /** Removes {@code fileName} and {@code fileName.tmp} from both folders; a missing file is no error. */
    public void remove(String fileName) {
        String name = requireFileName(fileName);
        withSftp(sftp -> {
            for (String folder : FOLDERS) {
                for (String candidate : List.of(name, name + TMP_SUFFIX)) {
                    String path = folder + "/" + candidate;
                    try {
                        sftp.rm(path);
                    } catch (SftpException e) {
                        if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                            throw new IllegalStateException("Cannot remove " + path + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static List<List<String>> expectedRecords(Path exampleJson) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readAllBytes(exampleJson));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read example " + exampleJson, e);
        }
        if (!root.isArray()) {
            throw new IllegalStateException(exampleJson + " is not a JSON array");
        }
        List<List<String>> records = new ArrayList<>();
        int index = 0;
        for (JsonNode object : root) {
            index++;
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            if (!COLUMNS.equals(keys)) {
                throw new IllegalStateException(exampleJson + " item " + index + " has keys " + keys + ", expected "
                    + COLUMNS);
            }
            List<String> values = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                JsonNode value = fields.next().getValue();
                if (value.isTextual()) {
                    values.add(value.textValue());
                } else if (value.isIntegralNumber()) {
                    values.add(value.bigIntegerValue().toString());
                } else {
                    throw new IllegalStateException(exampleJson + " item " + index
                        + " holds a value that is neither a string nor an integer: " + value);
                }
            }
            records.add(values);
        }
        return records;
    }

    private static String requireFileName(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Not a Pick List file name (PL-<order number>.csv): " + fileName);
        }
        return fileName;
    }

    private static boolean exists(ChannelSftp sftp, String path) {
        try {
            sftp.stat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw new IllegalStateException("Cannot stat " + path + ": " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface SftpWork<T> {
        T apply(ChannelSftp sftp);
    }

    private <T> T withSftp(SftpWork<T> work) {
        Session session = null;
        ChannelSftp channel = null;
        try {
            JSch jsch = new JSch();
            jsch.setKnownHosts(knownHostsFile.toString());
            session = jsch.getSession(username, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "password");
            session.setTimeout(TIMEOUT_MS);
            session.connect(TIMEOUT_MS);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(TIMEOUT_MS);
        } catch (JSchException e) {
            disconnect(channel, session);
            throw new IllegalStateException("Cannot connect to the Warehouse SFTP Server at " + host + ":" + port
                + " as " + username + " (strict host-key checking against " + knownHostsFile + "): "
                + e.getMessage(), e);
        }
        try {
            return work.apply(channel);
        } finally {
            disconnect(channel, session);
        }
    }

    private static void disconnect(ChannelSftp channel, Session session) {
        if (channel != null) {
            channel.disconnect();
        }
        if (session != null) {
            session.disconnect();
        }
    }
}
