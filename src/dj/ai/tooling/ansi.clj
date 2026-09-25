(ns dj.ai.tooling.ansi
  "Rendering of raw terminal output into plain text. Pure and total: no
  function here throws on any input. Terms are defined in doc/glossary.md
  and the contract in doc/design/terminal.md under Rendering.

      bytes --decode--> text --strip--> text --overwrite--> text

  `strip` removes escape sequences and controls with a small state machine.
  It is not a terminal emulator: cursor movement and erase commands are
  removed, not applied. `overwrite` applies carriage return and backspace
  within a line. `window` widens a byte range to line boundaries so that a
  cut never lands inside a sequence, and `render-range` composes the three
  steps while emitting only the characters of the requested range.")

(def ^:private replacement (int 0xFFFD))

(defn- continuation? [^bytes bytes i]
  (= 0x80 (bit-and 0xC0 (aget bytes i))))

(defn- sequence-length
  "UTF-8 sequence length for the well-formed sequence starting at `i`, or 0."
  [^bytes bytes i to]
  (let [b (bit-and 0xFF (aget bytes i))
        b1 (fn [] (bit-and 0xFF (aget bytes (inc i))))]
    (cond
      (< b 0x80) 1
      (and (<= 0xC2 b 0xDF) (< (+ i 1) to) (continuation? bytes (+ i 1))) 2
      (and (<= 0xE0 b 0xEF) (< (+ i 2) to)
           (continuation? bytes (+ i 1)) (continuation? bytes (+ i 2))
           (not (and (= b 0xE0) (< (b1) 0xA0)))
           (not (and (= b 0xED) (> (b1) 0x9F)))) 3
      (and (<= 0xF0 b 0xF4) (< (+ i 3) to)
           (continuation? bytes (+ i 1)) (continuation? bytes (+ i 2)) (continuation? bytes (+ i 3))
           (not (and (= b 0xF0) (< (b1) 0x90)))
           (not (and (= b 0xF4) (> (b1) 0x8F)))) 4
      :else 0)))

(defn- code-point [^bytes bytes i n]
  (let [b (fn [k] (bit-and 0xFF (aget bytes (+ i k))))]
    (case (int n)
      1 (b 0)
      2 (bit-or (bit-shift-left (bit-and 0x1F (b 0)) 6) (bit-and 0x3F (b 1)))
      3 (bit-or (bit-shift-left (bit-and 0x0F (b 0)) 12)
                (bit-shift-left (bit-and 0x3F (b 1)) 6)
                (bit-and 0x3F (b 2)))
      4 (bit-or (bit-shift-left (bit-and 0x07 (b 0)) 18)
                (bit-shift-left (bit-and 0x3F (b 1)) 12)
                (bit-shift-left (bit-and 0x3F (b 2)) 6)
                (bit-and 0x3F (b 3))))))

(defn decode
  "Decodes `bytes[from, to)` as UTF-8 into `{:text s :keep-from i :keep-to j}`
  where `i` and `j` are the character indexes at which the byte offsets
  `keep-from` and `keep-to` fall. A character is attributed to the offset of
  its first byte. Malformed bytes decode to U+FFFD one byte at a time."
  [^bytes bytes from to keep-from keep-to]
  (let [from (max 0 (min from (alength bytes)))
        to (max from (min to (alength bytes)))
        sb (StringBuilder. (- to from))]
    (loop [i from kf nil kt nil]
      (let [kf (if (and (nil? kf) (>= i keep-from)) (.length sb) kf)
            kt (if (and (nil? kt) (>= i keep-to)) (.length sb) kt)]
        (if (< i to)
          (let [n (sequence-length bytes i to)]
            (if (zero? n)
              (do (.appendCodePoint sb replacement) (recur (inc i) kf kt))
              (do (.appendCodePoint sb (code-point bytes i n)) (recur (+ i n) kf kt))))
          {:text (.toString sb)
           :keep-from (or kf (.length sb))
           :keep-to (or kt (.length sb))})))))

(def ^:private escape (int 0x1B))

(defn- between? [c lo hi] (and (>= c lo) (<= c hi)))

(defn- kept-control? [c]
  (or (= c 0x0A) (= c 0x09) (= c 0x0D) (= c 0x08)))

(defn strip
  "Removes escape sequences and controls from `text`, emitting only the
  characters whose index lies in `[keep-from, keep-to)`. Recognised: CSI
  (`ESC [` parameters, intermediates, final), OSC / DCS / APC / PM / SOS
  strings to BEL, `ESC \\`, or U+009C, and `ESC` with intermediates and a
  final byte. C0 controls other than newline, tab, carriage return, and
  backspace are dropped, as are DEL and C1. A newline ends any sequence
  and is kept, so an unterminated sequence never swallows the next line.
  A trailing incomplete sequence is dropped."
  ([^String text] (strip text 0 (.length text)))
  ([^String text keep-from keep-to]
   (let [n (.length text)
         sb (StringBuilder. n)
         emit (fn [i c] (when (and (>= i keep-from) (< i keep-to)) (.append sb (char c))))]
     (loop [i 0 state :ground]
       (when (< i n)
         (let [c (int (.charAt text i))]
           (case state
             :ground
             (cond
               (= c escape) (recur (inc i) :escape)
               (kept-control? c) (do (emit i c) (recur (inc i) :ground))
               (or (< c 0x20) (between? c 0x7F 0x9F)) (recur (inc i) :ground)
               :else (do (emit i c) (recur (inc i) :ground)))
             :escape
             (cond
               (= c escape) (recur (inc i) :escape)
               (= c 0x5B) (recur (inc i) :csi)
               (or (= c 0x5D) (= c 0x50) (= c 0x58) (= c 0x5E) (= c 0x5F)) (recur (inc i) :string)
               (between? c 0x20 0x2F) (recur (inc i) :escape)
               (between? c 0x30 0x7E) (recur (inc i) :ground)
               (kept-control? c) (do (emit i c) (recur (inc i) :ground))
               :else (recur (inc i) :ground))
             :csi
             (cond
               (= c escape) (recur (inc i) :escape)
               (between? c 0x20 0x3F) (recur (inc i) :csi)
               (between? c 0x40 0x7E) (recur (inc i) :ground)
               (kept-control? c) (do (emit i c) (recur (inc i) :ground))
               (< c 0x20) (recur (inc i) :csi)
               (between? c 0x7F 0x9F) (recur (inc i) :ground)
               :else (do (emit i c) (recur (inc i) :ground)))
             :string
             (cond
               (= c escape) (recur (inc i) :string-escape)
               (or (= c 0x07) (= c 0x9C)) (recur (inc i) :ground)
               (= c 0x0A) (do (emit i c) (recur (inc i) :ground))
               :else (recur (inc i) :string))
             :string-escape
             (if (= c 0x5C)
               (recur (inc i) :ground)
               (recur i :escape))))))
     (.toString sb))))

(defn overwrite
  "Applies carriage return and backspace within each line: carriage return
  moves the cursor to column zero, backspace one column back, and later
  characters overwrite at the cursor. Nothing crosses a newline."
  [^String text]
  (let [out (StringBuilder. (.length text))
        line (StringBuilder.)]
    (loop [i 0 cursor 0]
      (if (< i (.length text))
        (let [c (.charAt text i)]
          (case c
            \newline (do (.append out line) (.append out c) (.setLength line 0)
                         (recur (inc i) 0))
            \return (recur (inc i) 0)
            \backspace (recur (inc i) (max 0 (dec cursor)))
            (do (if (< cursor (.length line))
                  (.setCharAt line cursor c)
                  (.append line c))
                (recur (inc i) (inc cursor)))))
        (.toString (.append out line))))))

(defn render
  "`strip` then `overwrite`."
  [text]
  (overwrite (strip text)))

(defn window
  "Widens `[from, to)` over `bytes` to line boundaries: back to just after
  the previous newline (or the start) and forward to just after the next
  newline at or past `to` (or the end). Returns the widened range with the
  original range as the part to keep."
  [^bytes bytes from to]
  (let [n (alength bytes)
        from (max 0 (min from n))
        to (max from (min to n))
        start (loop [i (dec from)]
                (cond (< i 0) 0
                      (= 0x0A (aget bytes i)) (inc i)
                      :else (recur (dec i))))
        end (if (and (pos? to) (= 0x0A (aget bytes (dec to))))
              to
              (loop [i to]
                (cond (>= i n) n
                      (= 0x0A (aget bytes i)) (inc i)
                      :else (recur (inc i)))))]
    {:from start :to end :keep-from from :keep-to to}))

(defn strip-range
  "`decode` and `strip` over the widened window of `[from, to)`, emitting
  only characters that start inside the range."
  [^bytes bytes from to]
  (let [{:keys [from to keep-from keep-to]} (window bytes from to)
        {:keys [text keep-from keep-to]} (decode bytes from to keep-from keep-to)]
    (strip text keep-from keep-to)))

(defn render-range
  "`strip-range` then `overwrite`: the rendering of `bytes[from, to)`."
  [^bytes bytes from to]
  (overwrite (strip-range bytes from to)))
