package com.openai.livesubtitles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class CoreLogic {
    static final int MIN_OVERLAP_WORDS = 2;
    static final int CONTEXT_WORDS = 32;
    static final int MERGE_LOOKBACK_WORDS = 60;

    static final int MIN_SUBTITLE_WORDS = 4;
    static final int TARGET_SUBTITLE_WORDS = 8;
    static final int SOFT_MAX_SUBTITLE_WORDS = 11;
    static final int MAX_SUBTITLE_WORDS = 14;
    static final double PAUSE_FLUSH_SECONDS = 0.55;
    static final int MIN_PAUSE_FLUSH_WORDS = 4;
    static final int PREVIOUS_SOURCE_CONTEXT_WORDS = 26;
    static final int MAX_SUBTITLE_CHARS = 115;

    private static final Pattern SENTENCE_END =
            Pattern.compile("[.!?…][\"')\\]]*$");
    private static final Pattern SOFT_CLAUSE_END =
            Pattern.compile("[,;:][\"')\\]]*$");
    private static final Pattern EDGE_PUNCT =
            Pattern.compile("(^[^\\p{L}\\p{N}_А-Яа-яЁёÄÖÜäöüß]+)|([^\\p{L}\\p{N}_А-Яа-яЁёÄÖÜäöüß]+$)");

    private static final Set<String> BAD_END_WORDS = new HashSet<>(Arrays.asList(
            "a","an","the","and","or","but","if","that","which","who","whose",
            "with","without","for","from","to","of","in","on","at","by","as","than",
            "then","when","where","because","so","while","although","though","into",
            "upon","through","about","between","among","is","are","was","were","be",
            "been","being","has","have","had","will","would","can","could","should",
            "may","might","must","do","does","did","his","her","their","our","your","my"
    ));

    static String cleanText(String text) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    static String normalizedWord(String word) {
        if (word == null) return "";
        String lower = word.toLowerCase(Locale.ROOT);
        String previous;
        do {
            previous = lower;
            lower = EDGE_PUNCT.matcher(lower).replaceAll("");
        } while (!lower.equals(previous));
        return lower;
    }

    static List<String> tokenize(String text) {
        String cleaned = cleanText(text);
        List<String> result = new ArrayList<>();
        if (cleaned.isEmpty()) return result;
        for (String word : cleaned.split(" ")) {
            if (!normalizedWord(word).isEmpty()) result.add(word);
        }
        return result;
    }

    static String detokenize(List<String> words) {
        return cleanText(String.join(" ", words));
    }

    static boolean looksUseless(String text) {
        List<String> words = tokenize(text);
        List<String> norm = new ArrayList<>();
        for (String word : words) norm.add(normalizedWord(word));
        String normalized = String.join(" ", norm);
        if (normalized.length() < 2) return true;
        return new HashSet<>(Arrays.asList("hmm","hm","uh","um","you","mm","мм","м")).contains(normalized);
    }

    static List<String> dedupeWords(List<String> words) {
        if (words.size() < 2) return new ArrayList<>(words);
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            boolean removed = false;
            int maxSize = Math.min(5, (words.size() - i) / 2);
            for (int size = maxSize; size >= 1; size--) {
                boolean same = true;
                for (int k = 0; k < size; k++) {
                    if (!normalizedWord(words.get(i + k))
                            .equals(normalizedWord(words.get(i + size + k)))) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    result.addAll(words.subList(i, i + size));
                    i += size * 2;
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                result.add(words.get(i));
                i++;
            }
        }
        return result;
    }

    static boolean badPhraseEnd(String word) {
        return BAD_END_WORDS.contains(normalizedWord(word));
    }

    static String compactSubtitle(String text) {
        text = cleanText(text);
        if (text.length() <= MAX_SUBTITLE_CHARS) return text;
        text = text.substring(text.length() - MAX_SUBTITLE_CHARS);
        int firstSpace = text.indexOf(' ');
        if (firstSpace >= 0 && firstSpace <= 20) {
            text = text.substring(firstSpace + 1);
        }
        return text;
    }

    static final class TranscriptAssembler {
        private final List<String> contextWords = new ArrayList<>();
        private final List<String> pendingWords = new ArrayList<>();
        private long lastChangeNanos = System.nanoTime();

        private List<String> combined() {
            List<String> all = new ArrayList<>(contextWords);
            all.addAll(pendingWords);
            return all;
        }

        private Match bestMatch(List<String> existing, List<String> incoming) {
            if (existing.isEmpty() || incoming.isEmpty()) return null;
            int start = Math.max(0, existing.size() - MERGE_LOOKBACK_WORDS);
            int bestSize = 0, bestA = -1, bestB = -1;

            for (int a = start; a < existing.size(); a++) {
                for (int b = 0; b < incoming.size(); b++) {
                    int size = 0;
                    while (a + size < existing.size()
                            && b + size < incoming.size()
                            && normalizedWord(existing.get(a + size))
                            .equals(normalizedWord(incoming.get(b + size)))) {
                        size++;
                    }
                    if (size > bestSize) {
                        bestSize = size;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            return bestSize >= MIN_OVERLAP_WORDS ? new Match(bestA, bestB, bestSize) : null;
        }

        synchronized boolean add(String text) {
            List<String> incoming = dedupeWords(tokenize(text));
            if (incoming.isEmpty()) return false;

            long now = System.nanoTime();
            List<String> existing = combined();

            if (existing.isEmpty()) {
                pendingWords.clear();
                pendingWords.addAll(incoming);
                lastChangeNanos = now;
                return true;
            }

            Match match = bestMatch(existing, incoming);

            if (match == null) {
                if ((now - lastChangeNanos) / 1_000_000_000.0 >= PAUSE_FLUSH_SECONDS) {
                    pendingWords.addAll(incoming);
                    List<String> deduped = dedupeWords(pendingWords);
                    pendingWords.clear();
                    pendingWords.addAll(deduped);
                    lastChangeNanos = now;
                    return true;
                }
                return false;
            }

            int contextLen = contextWords.size();
            List<String> newPending = new ArrayList<>();

            if (match.a >= contextLen) {
                int pendingIndex = match.a - contextLen;
                newPending.addAll(pendingWords.subList(0, Math.min(pendingIndex, pendingWords.size())));
                newPending.addAll(incoming.subList(match.b, incoming.size()));
            } else {
                int overlapIntoContext = contextLen - match.a;
                int incomingStart = Math.max(0, match.b + overlapIntoContext);
                if (incomingStart < incoming.size()) {
                    newPending.addAll(incoming.subList(incomingStart, incoming.size()));
                }
            }

            newPending = dedupeWords(newPending);

            if (!normalizedList(pendingWords).equals(normalizedList(newPending))) {
                pendingWords.clear();
                pendingWords.addAll(newPending);
                lastChangeNanos = now;
                return true;
            }
            return false;
        }

        private List<String> normalizedList(List<String> words) {
            List<String> out = new ArrayList<>();
            for (String w : words) out.add(normalizedWord(w));
            return out;
        }

        synchronized double secondsSinceChange() {
            return (System.nanoTime() - lastChangeNanos) / 1_000_000_000.0;
        }

        synchronized String getContextText() {
            int start = Math.max(0, contextWords.size() - PREVIOUS_SOURCE_CONTEXT_WORDS);
            return detokenize(contextWords.subList(start, contextWords.size()));
        }

        synchronized String popWords(int count) {
            count = Math.min(count, pendingWords.size());
            if (count <= 0) return "";

            List<String> emitted = new ArrayList<>(pendingWords.subList(0, count));
            pendingWords.subList(0, count).clear();
            contextWords.addAll(emitted);

            if (contextWords.size() > CONTEXT_WORDS) {
                contextWords.subList(0, contextWords.size() - CONTEXT_WORDS).clear();
            }
            return detokenize(emitted);
        }

        synchronized int findSegmentLength(boolean forcePause) {
            List<String> words = new ArrayList<>(pendingWords);
            int count = words.size();
            if (count == 0) return 0;

            for (int index = 0; index < words.size(); index++) {
                int length = index + 1;
                if (length >= MIN_SUBTITLE_WORDS
                        && length <= MAX_SUBTITLE_WORDS
                        && SENTENCE_END.matcher(words.get(index)).find()) {
                    return length;
                }
            }

            if (count >= TARGET_SUBTITLE_WORDS) {
                int upper = Math.min(count, SOFT_MAX_SUBTITLE_WORDS);
                for (int index = upper - 1; index >= MIN_SUBTITLE_WORDS - 1; index--) {
                    if (SOFT_CLAUSE_END.matcher(words.get(index)).find()) {
                        return index + 1;
                    }
                }
            }

            if (count >= SOFT_MAX_SUBTITLE_WORDS) {
                for (int length = SOFT_MAX_SUBTITLE_WORDS;
                     length >= TARGET_SUBTITLE_WORDS; length--) {
                    if (!badPhraseEnd(words.get(length - 1))) return length;
                }
            }

            if (count >= MAX_SUBTITLE_WORDS) {
                for (int length = MAX_SUBTITLE_WORDS;
                     length >= TARGET_SUBTITLE_WORDS; length--) {
                    if (!badPhraseEnd(words.get(length - 1))) return length;
                }
                return MAX_SUBTITLE_WORDS;
            }

            if (forcePause && count >= MIN_PAUSE_FLUSH_WORDS) {
                if (!badPhraseEnd(words.get(words.size() - 1))) {
                    return Math.min(count, MAX_SUBTITLE_WORDS);
                }
            }

            return 0;
        }

        private static final class Match {
            final int a, b, size;
            Match(int a, int b, int size) {
                this.a = a;
                this.b = b;
                this.size = size;
            }
        }
    }
}
