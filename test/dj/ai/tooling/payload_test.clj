(ns dj.ai.tooling.payload-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dj.ai.tooling.payload :as payload]
            [dj.ai.tooling.payload.refs :as refs]
            [dj.ai.tooling.payload.strings :as strings]))

(defn block [id lang body] {:id id :lang lang :body body})
(defn doc [blocks lang body] {:blocks blocks :root {:lang lang :body body}})
(defn error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest resolves-nested-literals-and-forward-references
  (let [source "  print(\"héllo 🌍\")\r\npath = 'C:\\tmp\\f'\n"
        input (doc [(block "config" :json "{\"script\": {{python}}}")
                    (block "python" :python source)] :clojure "(load-config {{config}})")
        {:keys [final trace]} (payload/resolve input)
        config (second (edn/read-string final))]
    (is (= input (payload/validate input)))
    (is (= source (get (json/read-str config) "script")))
    (is (= [["python" source] ["config" config]] trace))
    (is (= final (:final (payload/resolve (update input :blocks #(vec (reverse %)))))))))

(deftest literal-references-are-never-expanded-twice
  (let [input (doc [(block "name" :text "wrong")
                    (block "literal" :text "\\{{name}} \\{{missing}} {{-invalid}}")]
                   :text "{{literal}} / {{ literal }}")]
    (is (= "{{name}} {{missing}} {{-invalid}} / {{name}} {{missing}} {{-invalid}}"
           (:final (payload/resolve input))))
    (is (= #{} (refs/refs-of "\\{{name}} {{-invalid}}"))))
  (is (= "\\{{name}}" (:final (payload/resolve (doc [] :text "\\\\{{name}}")))))
  (is (= "{{ broken" (:final (payload/resolve (doc [] :text "{{ broken"))))))

(deftest naked-references-and-positions
  (doseq [body ["\"{{x}}\"" "'{{x}}'" "a{{x}}'" "\"{{ x }}"]]
    (let [result (error #(payload/resolve (doc [(block "x" :text "value")] :json body)))]
      (is (= :quoted-reference (:reason result)))
      (is (= 1 (:at result)))
      (is (= :root (:block result)))))
  (is (= "\"{{x}}\"" (:final (payload/resolve (doc [] :text "\"\\{{x}}\""))))))

(deftest rejects-invalid-documents-before-resolution
  (doseq [[input reason]
          [[nil :invalid-document]
           [(doc nil :text "") :invalid-blocks]
           [(doc [nil] :text "") :invalid-block]
           [(doc [(block "bad id" :text "")] :text "") :invalid-block]
           [(doc [(block "x" :no-such-language "")] :text "") :unknown-language]
           [(doc [(block "x" :text "") (block "x" :text "")] :text "") :duplicate-id]
           [(doc [] :no-such-language "") :unknown-language]
           [(doc [] :text nil) :invalid-root]
           [(doc [] :text "prefix {{missing}}") :unknown-reference]
           [(doc [(block "unused" :text "{{missing}}") ] :text "") :unknown-reference]]]
    (is (= reason (:reason (error #(payload/resolve input)))) (pr-str input)))
  (is (= {:stage :validate :type :invalid-payload :reason :unknown-reference
          :block :root :ref "missing" :at 7}
         (error #(payload/resolve (doc [] :text "prefix {{missing}}"))))))

(deftest cycles-and-shared-dependencies
  (doseq [blocks [[(block "x" :text "{{x}}")]
                  [(block "x" :text "{{y}}") (block "y" :text "{{x}}")]]]
    (is (= :cycle (:reason (error #(payload/resolve (doc blocks :text "")))))))
  (let [result (payload/resolve (doc [(block "a" :text "{{c}}") (block "b" :text "{{c}}")
                                     (block "c" :text "value")] :text "{{a}} {{b}}"))]
    (is (= "value value" (:final result)))
    (is (= ["c" "a" "b"] (mapv first (:trace result))))))

(deftest limits-cover-input-expansion-and-depth
  (doseq [[input limits expected]
          [[(doc [(block "a" :text "") (block "b" :text "")] :text "") {:max-blocks 1} :max-blocks]
           [(doc [(block "a" :text "ab")] :text "cd") {:max-input-chars 3} :max-input-chars]
           [(doc [(block "a" :text "abcd")] :text "{{a}}{{a}}") {:max-output-chars 7} :max-output-chars]
           [(doc [(block "a" :text "abcd")] :text "{{a}}") {:max-total-chars 7} :max-total-chars]
           [(doc [(block "a" :text "abcd")] :json "{{a}}") {:max-output-chars 5} :max-output-chars]
           [(doc [(block "a" :text "{{b}}") (block "b" :text "")] :text "{{a}}") {:max-depth 2} :max-depth]]]
    (let [result (error #(payload/resolve input limits))]
      (is (= :limit-exceeded (:reason result)))
      (is (= expected (:limit result)))))
  (is (= "abcd" (:final (payload/resolve (doc [(block "a" :text "abcd")] :text "{{a}}")
                                         {:max-output-chars 4 :max-total-chars 8 :max-depth 2}))))
  (doseq [limits [nil {:bad-limit 3} {:max-depth 0} {:max-blocks 2.5}]]
    (is (= :invalid-limits (:reason (error #(payload/resolve (doc [] :text "") limits)))))))

(deftest depth-does-not-consume-the-jvm-stack
  (let [blocks (mapv #(block (str "b" %) :text (if (zero? %) "x" (str "{{b" (dec %) "}}"))) (range 1000))]
    (is (= "x" (:final (payload/resolve (doc blocks :text "{{b999}}")
                                       {:max-blocks 1000 :max-depth 1001}))))))

(deftest shell-nul-is-rejected-instead-of-silently-corrupting-a-value
  (doseq [lang [:bash :sh]]
    (is (= :unrepresentable-character
           (:reason (error #(payload/resolve (doc [(block "nul" :text (str (char 0)))] lang "{{nul}}")))))))
  (is (= (str (char 0)) (json/read-str (:final (payload/resolve (doc [(block "nul" :text (str (char 0)))] :json "{{nul}}")))))))
