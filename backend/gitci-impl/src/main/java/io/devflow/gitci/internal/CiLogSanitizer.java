package io.devflow.gitci.internal;

import static io.devflow.gitci.internal.GitHubActionsReadClient.Kind.UNAVAILABLE;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

@Component
final class CiLogSanitizer {
    static final int MAX_SNIPPET = 16_384;
    private static final int MAX_SUMMARY = 8192;
    private static final int MAX_CONTEXT_LINES = 20;
    private static final String REDACTED = "[redacted]";
    private static final Pattern ANSI = Pattern.compile("\u001b(?:\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|\\[[0-?]*+[ -/]*+[@-~]|[@-_])");
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");
    private static final Pattern KEY = Pattern.compile("(?is)-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]{0,40}PRIVATE KEY-----|\\z)");
    private static final Pattern HEADER = Pattern.compile("(?im)\\b(?:authorization|proxy-authorization|cookie|set-cookie)[^\\S\\n]*+:[^\\n]*+(?:\\n[ \\t]+[^\\n]*+)*");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(?:bearer|basic) +[A-Za-z0-9._~+/=-]{8,}+");
    private static final Pattern TOKEN = Pattern.compile("(?i)\\b(?:github_pat_|gh[pousr]_|sk[-_]|xox[bpars]-|hf_|AKIA|ASIA)[a-z0-9_-]{12,}+|"
            + "\\beyJ[a-z0-9_-]{8,}+\\.eyJ[a-z0-9_-]{8,}+\\.[a-z0-9_-]{8,}+");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)\\b[a-z0-9_.-]{0,64}(?:token|password|passwd|secret|api[_-]?key|credential|private[_-]?key)"
            + "[a-z0-9_.-]{0,32}[\"']?[^\\S\\n]*+[:=][^\\S\\n]*+(?:\"[^\"\\n]*+\\\"?|'[^'\\n]*+'?|[^\\s,;]+)");
    private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]{0,15}://[^\\s<>\"']++");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[a-z0-9._%+-]{1,64}@[a-z0-9.-]{1,253}\\.[a-z]{2,24}\\b");
    private static final Pattern LABELED_PII = Pattern.compile(
            "(?im)\\b(?:phone(?:_number)?|social[_ -]?security(?:[_ -]?number)?|ssn|home[_ -]?address)"
            + "[\"']?[^\\S\\n]*+[:=][^\\n]*");
    private static final Pattern ERROR = Pattern.compile("(?i)##\\[error]|::error\\b|\\b(?:error|failed|failure|exception|fatal|traceback|panic|assertion)\\b");
    private static final Pattern ARCHIVE = Pattern.compile("(?i).*\\.(zip|jar|war|ear|rar|7z|tar|gz|tgz|bz2|xz|zst|cab)$");
    private final GitHubActionsProperties properties;

    CiLogSanitizer(GitHubActionsProperties properties) { this.properties = properties; }

    record Excerpt(String text, String summary, int bytes, String digest, boolean truncated) {
        @Override public String toString() { return "Excerpt[bytes=" + bytes + ", truncated=" + truncated + "]"; }
    }

    Excerpt extract(GitHubActionsReadClient.Archive archive, List<String> bindingSecrets, long deadline) {
        NavigableMap<Long, NavigableMap<String, String>> groups = new TreeMap<>();
        var paths = new HashSet<String>();
        boolean truncated = archive.truncated();
        int expanded = 0;
        int entries = 0;
        int expectedEntries = endOfCentralDirectory(archive.bytes());
        List<Pattern> secrets = bindingSecrets.stream().filter(secret -> !secret.isBlank())
                .distinct().map(CiLogSanitizer::secretPattern).toList();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive.bytes()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                checkDeadline(deadline);
                if (++entries > properties.getMaxEntries() || entry.getSize() > properties.getMaxEntryBytes())
                    throw unavailable("LIMIT_EXCEEDED");
                String path = entry.getName();
                if (!safePath(path) || !paths.add(path) || ARCHIVE.matcher(path).matches()) throw unavailable("ARCHIVE_UNSAFE");
                byte[] data = zip.readNBytes(Math.min(properties.getMaxEntryBytes(), properties.getMaxExpandedBytes() - expanded) + 1);
                expanded += data.length;
                if (data.length > properties.getMaxEntryBytes() || expanded > properties.getMaxExpandedBytes())
                    throw unavailable("LIMIT_EXCEEDED");
                if (entry.isDirectory() && data.length != 0 || archiveMagic(data)) throw unavailable("ARCHIVE_UNSAFE");
                if (entry.isDirectory() || data.length == 0) continue;
                if (!path.toLowerCase(Locale.ROOT).endsWith(".txt") || containsNul(data)) {
                    truncated = true;
                    continue;
                }
                GitHubActionsReadClient.Job job = findJob(path, archive.jobs());
                if (job != null && !job.failed()) continue;
                String clean = redact(new String(data, StandardCharsets.UTF_8), secrets);
                List<String> steps = job == null ? List.of() : job.failedSteps();
                String context = context(clean, steps, deadline);
                if (context.isEmpty()) continue;
                groups.computeIfAbsent(job == null ? 0 : job.id(), ignored -> new TreeMap<>()).put(path, context);
                truncated |= !context.equals(clean.strip());
            }
        } catch (IOException | IllegalArgumentException ex) { throw unavailable("ARCHIVE_INVALID"); }
        if (entries != expectedEntries) throw unavailable("ARCHIVE_INVALID");
        if (groups.isEmpty()) throw unavailable("LOG_CONTEXT_MISSING");

        var output = new StringBuilder();
        int portion = MAX_SNIPPET / groups.size();
        int failedJob = 0;
        for (var group : groups.entrySet()) {
            String label = group.getKey() == 0 ? "[Workflow log, job unspecified]\n" : "[Failed job " + (++failedJob) + "]\n";
            output.append(label);
            var fragments = group.getValue().values();
            int fragmentBudget = Math.max(1, (portion - label.length()) / fragments.size() - 1);
            for (String fragment : fragments) {
                String clipped = limitUtf8(fragment, fragmentBudget);
                truncated |= !clipped.equals(fragment);
                if (!clipped.isEmpty()) output.append(clipped).append('\n');
            }
        }
        String full = output.toString().stripTrailing();
        String text = limitUtf8(full, MAX_SNIPPET);
        if (text.isEmpty()) throw unavailable("LOG_CONTEXT_MISSING");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new Excerpt(text, limitUtf8(text, MAX_SUMMARY), bytes.length, digest(bytes), truncated || !text.equals(full));
    }

    private static GitHubActionsReadClient.Job findJob(String path, List<GitHubActionsReadClient.Job> jobs) {
        int slash = path.indexOf('/');
        String name = slash >= 0 ? path.substring(0, slash) : path.replaceFirst("^\\d{1,5}_", "").replaceFirst("\\.txt$", "");
        GitHubActionsReadClient.Job result = null;
        for (var job : jobs) {
            if (!job.name().equals(name)) continue;
            if (result != null) return null;
            result = job;
        }
        return result;
    }

    private static String redact(String input, List<Pattern> secrets) {
        String clean = ANSI.matcher(input).replaceAll("");
        clean = CONTROLS.matcher(clean.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        for (Pattern secret : secrets) clean = secret.matcher(clean).replaceAll(REDACTED);
        clean = KEY.matcher(clean).replaceAll(REDACTED);
        clean = HEADER.matcher(clean).replaceAll(REDACTED);
        clean = AUTH.matcher(clean).replaceAll(REDACTED);
        clean = TOKEN.matcher(clean).replaceAll(REDACTED);
        clean = ASSIGNMENT.matcher(clean).replaceAll(REDACTED);
        clean = LABELED_PII.matcher(clean).replaceAll(REDACTED);
        clean = URL.matcher(clean).replaceAll(result -> {
            String value = result.group();
            return value.indexOf('@') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0
                    ? "[redacted URL]" : java.util.regex.Matcher.quoteReplacement(value);
        });
        return EMAIL.matcher(clean).replaceAll(REDACTED);
    }

    private static Pattern secretPattern(String credential) {
        String splitCredential = credential.codePoints().mapToObj(c -> Pattern.quote(Character.toString(c)))
                .collect(java.util.stream.Collectors.joining("[\\n\\t]{0,2}"));
        return Pattern.compile(splitCredential);
    }

    private static String context(String clean, List<String> failedSteps, long deadline) {
        var lines = clean.lines().toList();
        var selected = new java.util.TreeSet<Integer>();
        for (int i = lines.size() - 1; i >= 0 && selected.size() < MAX_CONTEXT_LINES; i--) {
            if ((i & 1023) == 0) checkDeadline(deadline);
            String line = lines.get(i);
            if (!(ERROR.matcher(line).find() || failedSteps.stream().anyMatch(line::contains))) continue;
            for (int nearby = Math.max(0, i - 2); nearby <= Math.min(i + 3, lines.size() - 1); nearby++) {
                String candidate = lines.get(nearby).strip();
                if (!candidate.isEmpty() && !candidate.replace(REDACTED, "").replace("[redacted URL]", "").isBlank())
                    selected.add(nearby);
            }
        }
        if (selected.isEmpty()) return "";
        return selected.stream().map(i -> {
            String line = lines.get(i).strip();
            var matcher = ERROR.matcher(line);
            if (matcher.find() && matcher.start() > 256) line = line.substring(Math.max(0, matcher.start() - 120));
            return limitUtf8(line, 1200);
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    static String limitUtf8(String value, int limit) {
        int offset = 0;
        int bytes = 0;
        while (offset < value.length()) {
            int code = value.codePointAt(offset);
            int size = code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
            if (bytes + size > limit) break;
            bytes += size;
            offset += Character.charCount(code);
        }
        return value.substring(0, offset);
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }
    private static boolean safePath(String path) {
        if (path.length() > 512 || path.isBlank() || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0 || path.codePoints().anyMatch(Character::isISOControl)) return false;
        for (String piece : path.split("/")) if (piece.isEmpty() || piece.equals(".") || piece.equals("..")) return false;
        return true;
    }
    private static boolean archiveMagic(byte[] bytes) {
        return bytes.length >= 4 && ((bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] < 9)
                || (bytes[0] == 0x1f && bytes[1] == (byte) 0x8b) || (bytes[0] == '7' && bytes[1] == 'z')
                || (bytes[0] == 'R' && bytes[1] == 'a' && bytes[2] == 'r' && bytes[3] == '!'));
    }
    private int endOfCentralDirectory(byte[] bytes) {
        for (int index = bytes.length - 22; index >= Math.max(0, bytes.length - 65_557); index--) {
            if (bytes[index] != 'P' || bytes[index+1] != 'K' || bytes[index+2] != 5 || bytes[index+3] != 6
                    || index + 22 + unsignedShort(bytes, index+20) != bytes.length) continue;
            int count = unsignedShort(bytes, index+10);
            if (unsignedShort(bytes, index+4) != 0 || unsignedShort(bytes, index+6) != 0
                    || unsignedShort(bytes, index+8) != count) throw unavailable("ARCHIVE_INVALID");
            if (count > properties.getMaxEntries()) throw unavailable("LIMIT_EXCEEDED");
            return count;
        }
        throw unavailable("ARCHIVE_INVALID");
    }
    private static int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset+1]) << 8);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void checkDeadline(long deadline) {
        if (System.nanoTime() - deadline >= 0) throw new GitHubActionsReadClient.FetchFailure(
                GitHubActionsReadClient.Kind.TRANSIENT, "CLAIM_TIMEOUT");
    }
    private static GitHubActionsReadClient.FetchFailure unavailable(String code) {
        return new GitHubActionsReadClient.FetchFailure(UNAVAILABLE, code);
    }
}
