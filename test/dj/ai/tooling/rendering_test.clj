(ns dj.ai.tooling.rendering-test
  (:require [clojure.test :refer [deftest is]]
            [dj.ai.tooling.rendering :as rendering]))

(deftest generated-text-is-what-extends-the-prompt-through-end-of-turn
  (let [prompt "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n<think>\n"]
    (is (= "why\n</think>\n\n<tool_call>x</tool_call><|im_end|>"
           (rendering/generated-text
            prompt
            (str "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n<think>\nwhy\n</think>\n\n<tool_call>x</tool_call><|im_end|>\n"
                 "<|im_start|>user\n<tool_response>\n(result)\n</tool_response><|im_end|>\n")
            "<|im_end|>"))
        "a reply with tool calls ends at its own end-of-turn, before the placeholder result")
    (is (= "why\n</think>\n\nHello<|im_end|>"
           (rendering/generated-text prompt (str prompt "why\n</think>\n\nHello") "<|im_end|>"))
        "a reply rendered as the final message gets the end-of-turn appended")
    (is (= "Hello" (rendering/generated-text "ab" "abHello" nil)))))
