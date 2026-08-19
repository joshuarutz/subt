import io
import os
import re
import sys
import time
import wave
import json
import base64
import queue
import threading

from collections import deque
from difflib import SequenceMatcher

import numpy as np
import requests
import pyaudiowpatch as pyaudio

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QFont
from PySide6.QtWidgets import (
    QApplication,
    QLabel,
    QWidget,
    QVBoxLayout,
)


# ============================================================
# OPENROUTER KEY
# ============================================================

OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY")


# ============================================================
# STT MODEL SELECTION
# ============================================================

STT_MODELS = {
    "1": (
        "Deepgram Nova-3",
        "deepgram/nova-3",
    ),

    "2": (
        "Microsoft MAI Transcribe 1.5",
        "microsoft/mai-transcribe-1.5",
    ),

    "3": (
        "Google Chirp 3",
        "google/chirp-3",
    ),

    # Lets you test any other OpenRouter STT model
    # without modifying the script.
    "4": (
        "Custom OpenRouter STT model",
        None,
    ),
}


def choose_stt_model():

    print()
    print("=" * 64)
    print("CHOOSE SPEECH-TO-TEXT MODEL")
    print("=" * 64)

    for key, (name, slug) in STT_MODELS.items():

        print()

        print(
            f"  {key}. {name}"
        )

        if slug:
            print(
                f"     {slug}"
            )

    print()

    choice = input(
        "STT model [1]: "
    ).strip()

    if not choice:
        choice = "1"

    if choice not in STT_MODELS:

        print(
            "Invalid choice. Using Nova-3."
        )

        choice = "1"

    name, slug = STT_MODELS[
        choice
    ]

    if slug is None:

        print()

        slug = input(
            "Enter OpenRouter STT model ID: "
        ).strip()

        if not slug:

            print(
                "No model entered. Using Nova-3."
            )

            name = "Deepgram Nova-3"

            slug = "deepgram/nova-3"

        else:

            name = slug

    return (
        name,
        slug,
    )


# Assigned in main().
STT_MODEL_NAME = ""
STT_MODEL = ""


# ============================================================
# TRANSLATION
# ============================================================

TRANSLATION_MODEL = (
    "google/gemini-2.5-flash-lite"
)

OUTPUT_LANGUAGE = "German"

# Explicitly disable reasoning where supported.
FORCE_NO_REASONING = True

TRANSLATION_MAX_TOKENS = 160

TRANSLATION_CONNECT_TIMEOUT = 2.5

TRANSLATION_READ_TIMEOUT = 7.0


# ============================================================
# STT / AUDIO TIMING
# ============================================================

# Recent system audio sent to STT.
AUDIO_WINDOW_SECONDS = 4.0

# Recognition frequency.
STT_INTERVAL_SECONDS = 0.60

MIN_AUDIO_SECONDS = 0.9

AUDIO_FRAME_MS = 50

SILENCE_RMS = 100


# ============================================================
# TRANSCRIPT ASSEMBLER
# ============================================================

MIN_OVERLAP_WORDS = 2

CONTEXT_WORDS = 32

MERGE_LOOKBACK_WORDS = 60


# ============================================================
# SUBTITLE SEGMENTATION
# ============================================================

MIN_SUBTITLE_WORDS = 4

# Aim around here, but DON'T blindly cut here.
TARGET_SUBTITLE_WORDS = 8

# Try to wait for a reasonable grammatical endpoint.
SOFT_MAX_SUBTITLE_WORDS = 11

# Absolute cutoff.
MAX_SUBTITLE_WORDS = 14

# If transcript stops developing, flush it.
PAUSE_FLUSH_SECONDS = 0.55

MIN_PAUSE_FLUSH_WORDS = 4


# ============================================================
# TRANSLATION CONTEXT
# ============================================================

# Hidden previous source context supplied to Gemini.
PREVIOUS_SOURCE_CONTEXT_WORDS = 26

# Also supply previous translated line.
USE_PREVIOUS_TRANSLATION_CONTEXT = True


# ============================================================
# SCROLLING VISUAL PANEL
# ============================================================

VISIBLE_SUBTITLES = 3

SUBTITLE_HISTORY_SIZE = 7

# Current/newest subtitle
CURRENT_FONT_SIZE = 21

# Previous subtitles
FONT_SIZE = 19

WINDOW_WIDTH = 880

WINDOW_HEIGHT = 155

BOTTOM_MARGIN = 55

# Remove transcript after prolonged silence.
SUBTITLE_HISTORY_TIMEOUT = 10.0

# Keep individual translated chunks reasonably compact.
MAX_SUBTITLE_CHARS = 115


# ============================================================
# DEBUG
# ============================================================

# True:
# shows HEARD / ASSEMBLED / QUEUED / CONTEXT in terminal.
#
# False:
# cleaner console.
DEBUG_TRANSCRIPT = True


# ============================================================
# OPENROUTER ENDPOINTS
# ============================================================

STT_URL = (
    "https://openrouter.ai/api/v1/"
    "audio/transcriptions"
)

CHAT_URL = (
    "https://openrouter.ai/api/v1/"
    "chat/completions"
)

stt_http = requests.Session()

translation_http = requests.Session()


# ============================================================
# TEXT HELPERS
# ============================================================

def clean_text(text):

    if text is None:
        return ""

    if not isinstance(text, str):
        text = str(text)

    text = re.sub(
        r"\s+",
        " ",
        text,
    ).strip()

    if (
        len(text) >= 2
        and text[0] == '"'
        and text[-1] == '"'
    ):

        text = text[
            1:-1
        ].strip()

    return text


def normalized_word(word):

    word = word.lower()

    word = re.sub(
        r"^[^\wА-Яа-яЁёÄÖÜäöüß]+",
        "",
        word,
    )

    word = re.sub(
        r"[^\wА-Яа-яЁёÄÖÜäöüß]+$",
        "",
        word,
    )

    return word


def tokenize(text):

    words = clean_text(
        text
    ).split()

    return [
        word
        for word in words
        if normalized_word(word)
    ]


def detokenize(words):

    return clean_text(
        " ".join(words)
    )


def ends_sentence(word):

    return bool(
        re.search(
            r'[.!?…]["\')\]]*$',
            word,
        )
    )


def ends_soft_clause(word):

    return bool(
        re.search(
            r'[,;:]["\')\]]*$',
            word,
        )
    )


# ============================================================
# BAD GRAMMATICAL ENDINGS
# ============================================================

BAD_END_WORDS = {

    "a",
    "an",
    "the",

    "and",
    "or",
    "but",

    "if",
    "that",
    "which",
    "who",
    "whose",

    "with",
    "without",

    "for",
    "from",
    "to",
    "of",

    "in",
    "on",
    "at",
    "by",

    "as",
    "than",
    "then",

    "when",
    "where",
    "because",
    "so",

    "while",
    "although",
    "though",

    "into",
    "upon",
    "through",
    "about",

    "between",
    "among",

    "is",
    "are",
    "was",
    "were",

    "be",
    "been",
    "being",

    "has",
    "have",
    "had",

    "will",
    "would",

    "can",
    "could",
    "should",

    "may",
    "might",
    "must",

    "do",
    "does",
    "did",

    "his",
    "her",
    "their",
    "our",
    "your",
    "my",
}


def bad_phrase_end(word):

    return (
        normalized_word(word)
        in BAD_END_WORDS
    )


def looks_useless(text):

    words = tokenize(
        text
    )

    normalized = " ".join(
        normalized_word(word)
        for word in words
    )

    if len(normalized) < 2:
        return True

    junk = {

        "hmm",
        "hm",

        "uh",
        "um",

        "you",

        "mm",

        "мм",
        "м",
    }

    return (
        normalized
        in junk
    )


# ============================================================
# DUPLICATE WORD CLEANUP
# ============================================================

def dedupe_words(words):

    """
    Removes simple rolling-window duplication such as:

        But But also
        persons who persons who
        In In myth
        God is God is
    """

    if len(words) < 2:
        return words

    result = []

    i = 0

    while i < len(words):

        removed = False

        max_size = min(
            5,
            (len(words) - i) // 2,
        )

        for size in range(
            max_size,
            0,
            -1,
        ):

            first = words[
                i:i + size
            ]

            second = words[
                i + size:
                i + size * 2
            ]

            first_norm = [
                normalized_word(x)
                for x in first
            ]

            second_norm = [
                normalized_word(x)
                for x in second
            ]

            if (
                first_norm
                ==
                second_norm
            ):

                result.extend(
                    first
                )

                i += (
                    size * 2
                )

                removed = True

                break

        if removed:
            continue

        result.append(
            words[i]
        )

        i += 1

    return result


# ============================================================
# AUDIO RING BUFFER
# ============================================================

class AudioRingBuffer:

    def __init__(
        self,
        sample_rate,
        channels,
        seconds,
    ):

        self.sample_rate = (
            sample_rate
        )

        self.channels = (
            channels
        )

        self.bytes_per_sample = 2

        self.max_bytes = int(
            sample_rate
            * channels
            * self.bytes_per_sample
            * seconds
        )

        self.buffers = deque()

        self.total_bytes = 0

        self.lock = (
            threading.Lock()
        )

    def append(self, data):

        with self.lock:

            self.buffers.append(
                data
            )

            self.total_bytes += (
                len(data)
            )

            while (
                self.buffers
                and
                self.total_bytes
                > self.max_bytes
            ):

                removed = (
                    self.buffers.popleft()
                )

                self.total_bytes -= (
                    len(removed)
                )

    def snapshot(self):

        with self.lock:

            return b"".join(
                self.buffers
            )

    def duration(self):

        with self.lock:

            divisor = (
                self.sample_rate
                * self.channels
                * self.bytes_per_sample
            )

            if not divisor:
                return 0.0

            return (
                self.total_bytes
                / divisor
            )


audio_ring = None


# ============================================================
# AUDIO HELPERS
# ============================================================

def pcm_rms(pcm):

    if not pcm:
        return 0.0

    samples = np.frombuffer(
        pcm,
        dtype=np.int16,
    ).astype(
        np.float32
    )

    if len(samples) == 0:
        return 0.0

    return float(
        np.sqrt(
            np.mean(
                samples
                * samples
            )
        )
    )


def pcm_to_wav(
    pcm,
    sample_rate,
    channels,
):

    output = io.BytesIO()

    with wave.open(
        output,
        "wb",
    ) as wav:

        wav.setnchannels(
            channels
        )

        wav.setsampwidth(
            2
        )

        wav.setframerate(
            sample_rate
        )

        wav.writeframes(
            pcm
        )

    return (
        output.getvalue()
    )


# ============================================================
# WINDOWS WASAPI LOOPBACK
# ============================================================

def find_loopback_device(p):

    try:

        device = (
            p.get_default_wasapi_loopback()
        )

        if device:
            return device

    except Exception:
        pass

    wasapi = (
        p.get_host_api_info_by_type(
            pyaudio.paWASAPI
        )
    )

    output_index = (
        wasapi[
            "defaultOutputDevice"
        ]
    )

    output = (
        p.get_device_info_by_index(
            output_index
        )
    )

    print()

    print(
        "Default Windows output:",
        output["name"],
    )

    if output.get(
        "isLoopbackDevice",
        False,
    ):
        return output

    loopbacks = list(
        p.get_loopback_device_info_generator()
    )

    for device in loopbacks:

        output_name = (
            output["name"]
            .replace(
                "[Loopback]",
                "",
            )
            .strip()
            .lower()
        )

        device_name = (
            device["name"]
            .replace(
                "[Loopback]",
                "",
            )
            .strip()
            .lower()
        )

        if (
            output_name
            in device_name
            or
            device_name
            in output_name
        ):

            return device

    if not loopbacks:

        raise RuntimeError(
            "No WASAPI loopback device found."
        )

    print(
        "Could not match default output exactly."
    )

    print(
        "Using:",
        loopbacks[0]["name"],
    )

    return loopbacks[0]


# ============================================================
# OPENROUTER STT
# ============================================================

def transcribe(wav_bytes):

    """
    Uses OpenRouter's JSON/base64 audio format.

    This is more portable between different OpenRouter
    transcription providers than depending on multipart
    behavior.
    """

    encoded_audio = (
        base64.b64encode(
            wav_bytes
        ).decode(
            "utf-8"
        )
    )

    payload = {

        "model":
            STT_MODEL,

        "input_audio": {

            "data":
                encoded_audio,

            "format":
                "wav",
        },
    }

    try:

        response = stt_http.post(

            STT_URL,

            headers={

                "Authorization":
                    f"Bearer "
                    f"{OPENROUTER_API_KEY}",

                "Content-Type":
                    "application/json",
            },

            json=payload,

            timeout=(
                3,
                10,
            ),
        )

        if not response.ok:

            print()
            print()

            print(
                "STT ERROR"
            )

            print(
                "Model:",
                STT_MODEL,
            )

            print(
                "HTTP:",
                response.status_code,
            )

            print(
                response.text[:1500]
            )

            return ""

        result = (
            response.json()
        )

        return clean_text(
            result.get(
                "text",
                "",
            )
        )

    except requests.Timeout:

        print()

        print(
            "STT TIMEOUT:",
            STT_MODEL,
        )

        return ""

    except Exception as exc:

        print()

        print(
            "STT ERROR:",
            repr(exc),
        )

        return ""


# ============================================================
# TRANSCRIPT ASSEMBLER
# ============================================================

class TranscriptAssembler:

    def __init__(self):

        self.lock = (
            threading.Lock()
        )

        # Already emitted transcript.
        self.context_words = []

        # Current un-emitted transcript.
        self.pending_words = []

        self.last_change = (
            time.monotonic()
        )

    def combined(self):

        return (
            self.context_words
            +
            self.pending_words
        )

    # --------------------------------------------------------

    def best_match(
        self,
        existing,
        incoming,
    ):

        if (
            not existing
            or
            not incoming
        ):

            return None

        start = max(
            0,
            len(existing)
            - MERGE_LOOKBACK_WORDS,
        )

        existing_tail = (
            existing[
                start:
            ]
        )

        existing_norm = [
            normalized_word(word)
            for word
            in existing_tail
        ]

        incoming_norm = [
            normalized_word(word)
            for word
            in incoming
        ]

        matcher = (
            SequenceMatcher(
                None,
                existing_norm,
                incoming_norm,
                autojunk=False,
            )
        )

        match = (
            matcher.find_longest_match(
                0,
                len(existing_norm),

                0,
                len(incoming_norm),
            )
        )

        if (
            match.size
            < MIN_OVERLAP_WORDS
        ):

            return None

        return (
            start + match.a,
            match.b,
            match.size,
        )

    # --------------------------------------------------------

    def add(self, text):

        incoming = tokenize(
            text
        )

        incoming = dedupe_words(
            incoming
        )

        if not incoming:
            return False

        now = (
            time.monotonic()
        )

        with self.lock:

            existing = (
                self.combined()
            )

            # First recognition.
            if not existing:

                self.pending_words = (
                    incoming
                )

                self.last_change = now

                return True

            match = self.best_match(
                existing,
                incoming,
            )

            # ------------------------------------------------
            # NO RELIABLE OVERLAP
            # ------------------------------------------------

            if match is None:

                # Don't immediately append random unstable
                # recognition.
                #
                # Only accept as new material after the
                # transcript has been stable for a while.

                if (
                    now
                    - self.last_change
                    >= PAUSE_FLUSH_SECONDS
                ):

                    self.pending_words.extend(
                        incoming
                    )

                    self.pending_words = (
                        dedupe_words(
                            self.pending_words
                        )
                    )

                    self.last_change = (
                        now
                    )

                    return True

                return False

            (
                existing_index,
                incoming_index,
                size,
            ) = match

            context_len = len(
                self.context_words
            )

            changed = False

            # ------------------------------------------------
            # MATCH STARTS INSIDE CURRENT PENDING TEXT
            # ------------------------------------------------

            if (
                existing_index
                >= context_len
            ):

                pending_index = (
                    existing_index
                    - context_len
                )

                new_pending = (

                    self.pending_words[
                        :pending_index
                    ]

                    +

                    incoming[
                        incoming_index:
                    ]
                )

                new_pending = (
                    dedupe_words(
                        new_pending
                    )
                )

                old_norm = [
                    normalized_word(x)
                    for x
                    in self.pending_words
                ]

                new_norm = [
                    normalized_word(x)
                    for x
                    in new_pending
                ]

                if (
                    old_norm
                    != new_norm
                ):

                    self.pending_words = (
                        new_pending
                    )

                    changed = True

            # ------------------------------------------------
            # MATCH STARTS IN OLD CONTEXT
            # ------------------------------------------------

            else:

                overlap_into_context = (
                    context_len
                    - existing_index
                )

                incoming_start = (
                    incoming_index
                    + overlap_into_context
                )

                incoming_start = max(
                    0,
                    incoming_start,
                )

                new_pending = incoming[
                    incoming_start:
                ]

                new_pending = (
                    dedupe_words(
                        new_pending
                    )
                )

                old_norm = [
                    normalized_word(x)
                    for x
                    in self.pending_words
                ]

                new_norm = [
                    normalized_word(x)
                    for x
                    in new_pending
                ]

                if (
                    old_norm
                    != new_norm
                ):

                    self.pending_words = (
                        new_pending
                    )

                    changed = True

            if changed:

                self.last_change = (
                    now
                )

            return changed

    # --------------------------------------------------------

    def pending_text(self):

        with self.lock:

            return detokenize(
                self.pending_words
            )

    # --------------------------------------------------------

    def seconds_since_change(self):

        with self.lock:

            return (
                time.monotonic()
                - self.last_change
            )

    # --------------------------------------------------------

    def get_context_text(self):

        with self.lock:

            context = (
                self.context_words[
                    -PREVIOUS_SOURCE_CONTEXT_WORDS:
                ]
            )

            return detokenize(
                context
            )

    # --------------------------------------------------------

    def pop_words(
        self,
        count,
    ):

        with self.lock:

            count = min(
                count,
                len(
                    self.pending_words
                ),
            )

            if count <= 0:
                return ""

            emitted = (
                self.pending_words[
                    :count
                ]
            )

            self.pending_words = (
                self.pending_words[
                    count:
                ]
            )

            self.context_words.extend(
                emitted
            )

            if (
                len(
                    self.context_words
                )
                > CONTEXT_WORDS
            ):

                self.context_words = (
                    self.context_words[
                        -CONTEXT_WORDS:
                    ]
                )

            return detokenize(
                emitted
            )

    # --------------------------------------------------------
    # GRAMMATICAL SEGMENTATION
    # --------------------------------------------------------

    def find_segment_length(
        self,
        force_pause=False,
    ):

        with self.lock:

            words = list(
                self.pending_words
            )

        count = len(
            words
        )

        if count == 0:
            return 0

        # ====================================================
        # 1. SENTENCE END
        # ====================================================

        for index, word in enumerate(
            words
        ):

            length = (
                index + 1
            )

            if (
                length
                >= MIN_SUBTITLE_WORDS

                and

                length
                <= MAX_SUBTITLE_WORDS

                and

                ends_sentence(
                    word
                )
            ):

                return length

        # ====================================================
        # 2. COMMA / NATURAL CLAUSE
        # ====================================================

        if (
            count
            >= TARGET_SUBTITLE_WORDS
        ):

            upper = min(
                count,
                SOFT_MAX_SUBTITLE_WORDS,
            )

            for index in range(
                upper - 1,
                MIN_SUBTITLE_WORDS - 2,
                -1,
            ):

                if (
                    ends_soft_clause(
                        words[index]
                    )
                ):

                    return (
                        index + 1
                    )

        # ====================================================
        # 3. SOFT MAX
        # ====================================================

        if (
            count
            >= SOFT_MAX_SUBTITLE_WORDS
        ):

            for length in range(
                SOFT_MAX_SUBTITLE_WORDS,
                TARGET_SUBTITLE_WORDS - 1,
                -1,
            ):

                if (
                    not bad_phrase_end(
                        words[
                            length - 1
                        ]
                    )
                ):

                    return length

        # ====================================================
        # 4. HARD MAX
        # ====================================================

        if (
            count
            >= MAX_SUBTITLE_WORDS
        ):

            for length in range(
                MAX_SUBTITLE_WORDS,
                TARGET_SUBTITLE_WORDS - 1,
                -1,
            ):

                if (
                    not bad_phrase_end(
                        words[
                            length - 1
                        ]
                    )
                ):

                    return length

            return (
                MAX_SUBTITLE_WORDS
            )

        # ====================================================
        # 5. SPEECH PAUSE
        # ====================================================

        if (
            force_pause
            and
            count
            >= MIN_PAUSE_FLUSH_WORDS
        ):

            # If phrase has a sensible ending,
            # emit it.
            if (
                not bad_phrase_end(
                    words[-1]
                )
            ):

                return min(
                    count,
                    MAX_SUBTITLE_WORDS,
                )

            # Otherwise give speaker more time to finish.
            return 0

        return 0


assembler = (
    TranscriptAssembler()
)


# ============================================================
# TRANSLATION HISTORY
# ============================================================

class TranslationHistory:

    def __init__(self):

        self.lock = (
            threading.Lock()
        )

        self.previous_source = ""

        self.previous_translation = ""

    def get(self):

        with self.lock:

            return (
                self.previous_source,
                self.previous_translation,
            )

    def set(
        self,
        source,
        translation,
    ):

        with self.lock:

            self.previous_source = (
                source
            )

            self.previous_translation = (
                translation
            )


translation_history = (
    TranslationHistory()
)


# ============================================================
# TRANSLATION INPUT ITEM
# ============================================================

class SubtitleSource:

    def __init__(
        self,
        text,
        previous_source_context,
        previous_translation,
    ):

        self.text = (
            text
        )

        self.previous_source_context = (
            previous_source_context
        )

        self.previous_translation = (
            previous_translation
        )


# ============================================================
# LATEST-ONLY SOURCE QUEUE
# ============================================================

source_queue = (
    queue.Queue(
        maxsize=1
    )
)


def queue_source(item):

    # Never accumulate old untranslated subtitles.

    try:

        while True:

            source_queue.get_nowait()

    except queue.Empty:
        pass

    try:

        source_queue.put_nowait(
            item
        )

    except queue.Full:
        pass


# ============================================================
# SCROLLING DISPLAY STATE
# ============================================================

class DisplayState:

    def __init__(self):

        self.lock = (
            threading.Lock()
        )

        self.entries = deque(
            maxlen=
                SUBTITLE_HISTORY_SIZE
        )

        self.running = True

    def add(
        self,
        subtitle,
        source,
    ):

        subtitle = clean_text(
            subtitle
        )

        source = clean_text(
            source
        )

        if not subtitle:
            return

        now = (
            time.monotonic()
        )

        with self.lock:

            # Exact consecutive duplicate.
            if self.entries:

                previous = (
                    self.entries[-1]
                )

                if (
                    clean_text(
                        previous[
                            "subtitle"
                        ]
                    )
                    ==
                    subtitle
                ):

                    previous[
                        "updated_at"
                    ] = now

                    return

            self.entries.append(
                {

                    "subtitle":
                        subtitle,

                    "source":
                        source,

                    "created_at":
                        now,

                    "updated_at":
                        now,
                }
            )

    def get_entries(self):

        now = (
            time.monotonic()
        )

        with self.lock:

            # Clear after prolonged silence.
            if self.entries:

                newest_age = (
                    now
                    -
                    self.entries[-1][
                        "updated_at"
                    ]
                )

                if (
                    newest_age
                    >
                    SUBTITLE_HISTORY_TIMEOUT
                ):

                    self.entries.clear()

            return list(
                self.entries
            )


display_state = (
    DisplayState()
)


# ============================================================
# SEGMENTER WORKER
# ============================================================

def segmenter_worker():

    while display_state.running:

        time.sleep(
            0.05
        )

        pause = (
            assembler
            .seconds_since_change()
            >=
            PAUSE_FLUSH_SECONDS
        )

        segment_length = (
            assembler
            .find_segment_length(
                force_pause=pause
            )
        )

        if (
            segment_length
            <= 0
        ):

            continue

        # Context BEFORE current chunk is popped.
        previous_context = (
            assembler
            .get_context_text()
        )

        source = (
            assembler.pop_words(
                segment_length
            )
        )

        if not source:
            continue

        (
            previous_source,
            previous_translation,
        ) = (
            translation_history.get()
        )

        context = (
            previous_context
        )

        # Add previous translated chunk's source to context
        # if it isn't already represented.

        if (
            previous_source

            and

            previous_source
            not in context
        ):

            context = clean_text(
                context
                + " "
                + previous_source
            )

            context_words = (
                context.split()
            )

            context = " ".join(
                context_words[
                    -PREVIOUS_SOURCE_CONTEXT_WORDS:
                ]
            )

        item = SubtitleSource(

            text=
                source,

            previous_source_context=
                context,

            previous_translation=
                previous_translation,
        )

        if DEBUG_TRANSCRIPT:

            print()

            print(
                "QUEUED:",
                source,
            )

            if context:

                print(
                    "CONTEXT:",
                    context,
                )

        queue_source(
            item
        )


# ============================================================
# TRANSLATION REQUEST
# ============================================================

def build_translation_payload(
    item,
    use_no_reasoning=True,
):

    system_prompt = f"""
You are a professional real-time subtitle translator.

Translate ONLY CURRENT SOURCE into {OUTPUT_LANGUAGE}.

The source language can be English, German, or Russian.

PREVIOUS SOURCE CONTEXT and PREVIOUS TRANSLATION are context only.

Use previous context to understand:
- sentence continuation
- grammar
- pronouns
- subjects and objects
- verb phrases
- references
- intended meaning

Never translate the previous context again.

Output ONLY the translation of CURRENT SOURCE.

CURRENT SOURCE may be a continuation of the previous subtitle.
Make the translation connect naturally when appropriate.

Rules:
- No explanation.
- No analysis.
- No labels.
- No quotation marks.
- Do not repeat previous subtitles.
- Do not invent missing speech.
- Preserve names.
- Preserve numbers.
- Preserve negation.
- Preserve meaning.
- Keep wording concise and natural.
- Write like professional film subtitles.
""".strip()

    previous_translation = (
        item.previous_translation

        if (
            USE_PREVIOUS_TRANSLATION_CONTEXT
            and
            item.previous_translation
        )

        else
        "[none]"
    )

    previous_source = (
        item.previous_source_context

        if
        item.previous_source_context

        else
        "[none]"
    )

    user_content = (
        "PREVIOUS SOURCE CONTEXT:\n"
        + previous_source

        + "\n\n"

        + "PREVIOUS TRANSLATION:\n"
        + previous_translation

        + "\n\n"

        + "CURRENT SOURCE:\n"
        + item.text
    )

    payload = {

        "model":
            TRANSLATION_MODEL,

        "messages": [

            {
                "role":
                    "system",

                "content":
                    system_prompt,
            },

            {
                "role":
                    "user",

                "content":
                    user_content,
            },
        ],

        "temperature":
            0,

        "max_tokens":
            TRANSLATION_MAX_TOKENS,
    }

    if (
        FORCE_NO_REASONING
        and
        use_no_reasoning
    ):

        payload[
            "reasoning"
        ] = {

            "effort":
                "none",

            "exclude":
                True,
        }

    return payload


# ============================================================
# EXTRACT MODEL OUTPUT
# ============================================================

def extract_translation(
    result
):

    try:

        message = (
            result[
                "choices"
            ][0][
                "message"
            ]
        )

        content = (
            message.get(
                "content",
                "",
            )
        )

    except Exception:

        return ""

    if isinstance(
        content,
        str,
    ):

        return clean_text(
            content
        )

    if isinstance(
        content,
        list,
    ):

        pieces = []

        for part in content:

            if isinstance(
                part,
                str,
            ):

                pieces.append(
                    part
                )

            elif isinstance(
                part,
                dict,
            ):

                text = (
                    part.get(
                        "text",
                        "",
                    )
                )

                if isinstance(
                    text,
                    str,
                ):

                    pieces.append(
                        text
                    )

        return clean_text(
            " ".join(
                pieces
            )
        )

    return ""


# ============================================================
# TRANSLATE
# ============================================================

def translation_request(
    item,
    use_no_reasoning=True,
):

    try:

        response = (
            translation_http.post(

                CHAT_URL,

                headers={

                    "Authorization":
                        f"Bearer "
                        f"{OPENROUTER_API_KEY}",

                    "Content-Type":
                        "application/json",
                },

                json=
                    build_translation_payload(
                        item,
                        use_no_reasoning,
                    ),

                timeout=(
                    TRANSLATION_CONNECT_TIMEOUT,
                    TRANSLATION_READ_TIMEOUT,
                ),
            )
        )

    except requests.Timeout:

        return (
            "",
            "timeout",
        )

    except Exception as exc:

        return (
            "",
            repr(exc),
        )

    if not response.ok:

        return (
            "",
            (
                f"HTTP "
                f"{response.status_code}: "
                f"{response.text[:1000]}"
            ),
        )

    try:

        result = (
            response.json()
        )

    except Exception:

        return (
            "",
            "invalid JSON response",
        )

    translated = (
        extract_translation(
            result
        )
    )

    if not translated:

        return (
            "",
            "empty translation",
        )

    return (
        translated,
        "",
    )


def translate(item):

    started = (
        time.monotonic()
    )

    translated, error = (
        translation_request(
            item,
            use_no_reasoning=True,
        )
    )

    # Retry without reasoning parameter if provider
    # rejects it.

    if (
        not translated
        and
        FORCE_NO_REASONING
    ):

        translated, error2 = (
            translation_request(
                item,
                use_no_reasoning=False,
            )
        )

        if not translated:

            error = (
                error
                + " | retry: "
                + error2
            )

    elapsed = (
        time.monotonic()
        - started
    )

    if not translated:

        print()

        print(
            "TRANSLATION FAILED:",
            error,
        )

        print(
            "SOURCE:",
            item.text,
        )

        return ""

    if DEBUG_TRANSCRIPT:

        print()

        print(
            "SOURCE:   ",
            item.text,
        )

        print(
            "SUBTITLE: ",
            translated,
        )

        print(
            "TRANSLATION TIME:",
            f"{elapsed:.2f}s",
        )

    return translated


# ============================================================
# TRANSLATION WORKER
# ============================================================

translated_queue = (
    queue.Queue(
        maxsize=1
    )
)


def translation_worker():

    while display_state.running:

        try:

            item = (
                source_queue.get(
                    timeout=0.4
                )
            )

        except queue.Empty:

            continue

        translated = (
            translate(
                item
            )
        )

        if not translated:
            continue

        # Save context for next translation.

        translation_history.set(
            item.text,
            translated,
        )

        # Latest only.
        try:

            while True:
                translated_queue.get_nowait()

        except queue.Empty:
            pass

        try:

            translated_queue.put_nowait(
                (
                    item.text,
                    translated,
                )
            )

        except queue.Full:
            pass


# ============================================================
# PRESENTER WORKER
# ============================================================

def presenter_worker():

    while display_state.running:

        try:

            (
                source,
                translated,
            ) = (
                translated_queue.get(
                    timeout=0.3
                )
            )

        except queue.Empty:

            continue

        # Consume newer result if one arrived between
        # queue wake-up and display.

        try:

            while True:

                (
                    newer_source,
                    newer_translation,
                ) = (
                    translated_queue
                    .get_nowait()
                )

                source = (
                    newer_source
                )

                translated = (
                    newer_translation
                )

        except queue.Empty:
            pass

        # No artificial display wait.
        #
        # Scrolling transcript preserves previous text visually.

        display_state.add(
            translated,
            source,
        )


# ============================================================
# STT WORKER
# ============================================================

def stt_worker(
    sample_rate,
    channels,
):

    last_source = ""

    time.sleep(
        MIN_AUDIO_SECONDS
    )

    while display_state.running:

        started = (
            time.monotonic()
        )

        pcm = (
            audio_ring.snapshot()
        )

        duration = (
            audio_ring.duration()
        )

        if (
            duration
            >= MIN_AUDIO_SECONDS
            and
            pcm
        ):

            level = (
                pcm_rms(
                    pcm
                )
            )

            if (
                level
                >= SILENCE_RMS
            ):

                wav_bytes = (
                    pcm_to_wav(
                        pcm,
                        sample_rate,
                        channels,
                    )
                )

                source = (
                    transcribe(
                        wav_bytes
                    )
                )

                if (
                    source

                    and

                    not looks_useless(
                        source
                    )
                ):

                    if (
                        clean_text(
                            source
                        )
                        !=
                        clean_text(
                            last_source
                        )
                    ):

                        last_source = (
                            source
                        )

                        if DEBUG_TRANSCRIPT:

                            print(
                                "\rHEARD:",
                                source[:150]
                                .ljust(150),
                                end="",
                                flush=True,
                            )

                        changed = (
                            assembler.add(
                                source
                            )
                        )

                        if (
                            changed
                            and
                            DEBUG_TRANSCRIPT
                        ):

                            print()

                            print(
                                "ASSEMBLED:",
                                assembler
                                .pending_text(),
                            )

        elapsed = (
            time.monotonic()
            - started
        )

        time.sleep(
            max(
                0.03,
                STT_INTERVAL_SECONDS
                - elapsed,
            )
        )


# ============================================================
# AUDIO CALLBACK
# ============================================================

def audio_callback(
    in_data,
    frame_count,
    time_info,
    status_flags,
):

    if (
        audio_ring
        is not None
    ):

        audio_ring.append(
            in_data
        )

    return (
        None,
        pyaudio.paContinue,
    )


# ============================================================
# COMPACT SUBTITLE TEXT
# ============================================================

def compact_subtitle(text):

    text = clean_text(
        text
    )

    if not text:
        return ""

    if (
        len(text)
        <= MAX_SUBTITLE_CHARS
    ):

        return text

    text = text[
        -MAX_SUBTITLE_CHARS:
    ]

    first_space = (
        text.find(" ")
    )

    if (
        0
        <= first_space
        <= 20
    ):

        text = (
            text[
                first_space + 1:
            ]
        )

    return text


# ============================================================
# SCROLLING OVERLAY
# ============================================================

class SubtitleOverlay(QWidget):

    def __init__(self):

        super().__init__()

        self.drag_position = None

        self.last_signature = None

        # ----------------------------------------------------
        # WINDOW
        # ----------------------------------------------------

        self.setWindowFlags(

            Qt.WindowType.FramelessWindowHint

            |

            Qt.WindowType.WindowStaysOnTopHint

            |

            Qt.WindowType.Tool
        )

        self.setAttribute(
            Qt.WidgetAttribute.WA_TranslucentBackground
        )

        self.resize(
            WINDOW_WIDTH,
            WINDOW_HEIGHT,
        )

        # ----------------------------------------------------
        # LAYOUT
        # ----------------------------------------------------

        self.main_layout = (
            QVBoxLayout()
        )

        self.main_layout.setContentsMargins(
            10,
            7,
            10,
            7,
        )

        self.main_layout.setSpacing(
            2
        )

        self.labels = []

        for index in range(
            VISIBLE_SUBTITLES
        ):

            label = QLabel(
                ""
            )

            label.setWordWrap(
                True
            )

            label.setAlignment(
                Qt.AlignmentFlag.AlignLeft
                |
                Qt.AlignmentFlag.AlignVCenter
            )

            self.labels.append(
                label
            )

            self.main_layout.addWidget(
                label
            )

        self.setLayout(
            self.main_layout
        )

        self.update_styles()

        self.position_window()

        # ----------------------------------------------------
        # REFRESH
        # ----------------------------------------------------

        self.timer = (
            QTimer(
                self
            )
        )

        self.timer.timeout.connect(
            self.refresh
        )

        self.timer.start(
            40
        )

    # --------------------------------------------------------
    # VISUAL STYLE
    # --------------------------------------------------------

    def update_styles(self):

        total = len(
            self.labels
        )

        for index, label in enumerate(
            self.labels
        ):

            distance = (
                total
                - index
                - 1
            )

            # Newest
            if distance == 0:

                font_size = (
                    CURRENT_FONT_SIZE
                )

                opacity = 255

                background = 190

                weight = (
                    QFont.Weight.DemiBold
                )

            # Previous
            elif distance == 1:

                font_size = (
                    FONT_SIZE
                )

                opacity = 190

                background = 145

                weight = (
                    QFont.Weight.Normal
                )

            # Oldest
            else:

                font_size = (
                    FONT_SIZE - 1
                )

                opacity = 115

                background = 105

                weight = (
                    QFont.Weight.Normal
                )

            font = QFont()

            font.setPointSize(
                font_size
            )

            font.setWeight(
                weight
            )

            label.setFont(
                font
            )

            label.setStyleSheet(
                f"""
                QLabel {{
                    color:
                        rgba(
                            255,
                            255,
                            255,
                            {opacity}
                        );

                    background-color:
                        rgba(
                            0,
                            0,
                            0,
                            {background}
                        );

                    border-radius:
                        5px;

                    padding:
                        3px 7px;
                }}
                """
            )

    # --------------------------------------------------------
    # POSITION
    # --------------------------------------------------------

    def position_window(self):

        screen = (
            QApplication
            .primaryScreen()
            .availableGeometry()
        )

        x = (
            screen.x()
            +
            (
                screen.width()
                - self.width()
            ) // 2
        )

        y = (
            screen.y()
            + screen.height()
            - self.height()
            - BOTTOM_MARGIN
        )

        self.move(
            x,
            y,
        )

    # --------------------------------------------------------
    # UPDATE
    # --------------------------------------------------------

    def refresh(self):

        entries = (
            display_state
            .get_entries()
        )

        entries = (
            entries[
                -VISIBLE_SUBTITLES:
            ]
        )

        signature = tuple(
            entry[
                "subtitle"
            ]
            for entry
            in entries
        )

        if (
            signature
            ==
            self.last_signature
        ):

            return

        self.last_signature = (
            signature
        )

        empty_count = (
            VISIBLE_SUBTITLES
            - len(entries)
        )

        for index in range(
            VISIBLE_SUBTITLES
        ):

            label = (
                self.labels[
                    index
                ]
            )

            entry_index = (
                index
                - empty_count
            )

            if (
                entry_index
                < 0
            ):

                label.clear()

                label.hide()

                continue

            entry = (
                entries[
                    entry_index
                ]
            )

            text = (
                compact_subtitle(
                    entry[
                        "subtitle"
                    ]
                )
            )

            label.setText(
                text
            )

            label.show()

        self.update_styles()

    # --------------------------------------------------------
    # DRAGGING
    # --------------------------------------------------------

    def mousePressEvent(
        self,
        event,
    ):

        if (
            event.button()
            ==
            Qt.MouseButton.LeftButton
        ):

            self.drag_position = (

                event
                .globalPosition()
                .toPoint()

                -

                self
                .frameGeometry()
                .topLeft()
            )

            event.accept()

    def mouseMoveEvent(
        self,
        event,
    ):

        if (
            self.drag_position
            is not None

            and

            (
                event.buttons()
                &
                Qt.MouseButton.LeftButton
            )
        ):

            self.move(

                event
                .globalPosition()
                .toPoint()

                -

                self.drag_position
            )

            event.accept()

    def mouseReleaseEvent(
        self,
        event,
    ):

        self.drag_position = None

        event.accept()

    # --------------------------------------------------------
    # CLOSE
    # --------------------------------------------------------

    def closeEvent(
        self,
        event,
    ):

        display_state.running = (
            False
        )

        event.accept()


# ============================================================
# MAIN
# ============================================================

def main():

    global audio_ring
    global STT_MODEL
    global STT_MODEL_NAME

    if not OPENROUTER_API_KEY:

        print()

        print(
            "ERROR: OPENROUTER_API_KEY is not set."
        )

        print()

        print(
            "Windows CMD:"
        )

        print(
            "set OPENROUTER_API_KEY=sk-or-v1-..."
        )

        return 1

    # ========================================================
    # MODEL SELECTION
    # ========================================================

    (
        STT_MODEL_NAME,
        STT_MODEL,
    ) = (
        choose_stt_model()
    )

    print()
    print("=" * 72)
    print("SCROLLING LIVE SUBTITLES")
    print("=" * 72)

    print(
        "STT:",
        STT_MODEL_NAME,
    )

    print(
        "Model ID:",
        STT_MODEL,
    )

    print(
        "Translation:",
        TRANSLATION_MODEL,
    )

    print(
        "Output:",
        OUTPUT_LANGUAGE,
    )

    print(
        "STT interval:",
        STT_INTERVAL_SECONDS,
        "seconds",
    )

    print(
        "Audio window:",
        AUDIO_WINDOW_SECONDS,
        "seconds",
    )

    print(
        "Visible subtitles:",
        VISIBLE_SUBTITLES,
    )

    # ========================================================
    # AUDIO
    # ========================================================

    p = (
        pyaudio.PyAudio()
    )

    stream = None

    try:

        device = (
            find_loopback_device(
                p
            )
        )

        device_index = int(
            device[
                "index"
            ]
        )

        sample_rate = int(
            device[
                "defaultSampleRate"
            ]
        )

        channels = min(
            2,
            max(
                1,
                int(
                    device[
                        "maxInputChannels"
                    ]
                ),
            ),
        )

        frames_per_buffer = int(
            sample_rate
            * AUDIO_FRAME_MS
            / 1000
        )

        print()

        print(
            "Capturing:",
            device[
                "name"
            ],
        )

        print(
            "Sample rate:",
            sample_rate,
        )

        print(
            "Channels:",
            channels,
        )

        # ====================================================
        # RING BUFFER
        # ====================================================

        audio_ring = (
            AudioRingBuffer(

                sample_rate,

                channels,

                AUDIO_WINDOW_SECONDS,
            )
        )

        # ====================================================
        # AUDIO STREAM
        # ====================================================

        stream = p.open(

            format=
                pyaudio.paInt16,

            channels=
                channels,

            rate=
                sample_rate,

            input=
                True,

            input_device_index=
                device_index,

            frames_per_buffer=
                frames_per_buffer,

            stream_callback=
                audio_callback,
        )

        stream.start_stream()

        # ====================================================
        # WORKERS
        # ====================================================

        workers = [

            threading.Thread(

                target=
                    stt_worker,

                args=(
                    sample_rate,
                    channels,
                ),

                daemon=True,
            ),

            threading.Thread(

                target=
                    segmenter_worker,

                daemon=True,
            ),

            threading.Thread(

                target=
                    translation_worker,

                daemon=True,
            ),

            threading.Thread(

                target=
                    presenter_worker,

                daemon=True,
            ),
        ]

        for worker in workers:

            worker.start()

        print()

        print(
            "Play the video."
        )

        print()

        print(
            "Newest subtitle appears at the bottom."
        )

        print(
            "Older subtitles move upward and fade."
        )

        print(
            "Drag the subtitle panel with the mouse."
        )

        print(
            "Close it to quit."
        )

        print("=" * 72)

        # ====================================================
        # QT
        # ====================================================

        app = (
            QApplication(
                sys.argv
            )
        )

        overlay = (
            SubtitleOverlay()
        )

        overlay.show()

        return (
            app.exec()
        )

    except KeyboardInterrupt:

        return 0

    finally:

        display_state.running = (
            False
        )

        if (
            stream
            is not None
        ):

            try:
                stream.stop_stream()

            except Exception:
                pass

            try:
                stream.close()

            except Exception:
                pass

        p.terminate()


# ============================================================
# START
# ============================================================

if __name__ == "__main__":

    sys.exit(
        main()
    )