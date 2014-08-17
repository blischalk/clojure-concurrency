(ns concurrency.core)

; FUTURES: Place a task on another thread
; It is only run once and its result is cached

; Use future to put task on another thread
(do (future (Thread/sleep 4000)
            (println "I'll print after 4 seconds"))
    (println "I'll print immediately"))


; You must dereference a value to get a futures value
(let [result (future (println "this prints once")
                     (+ 1 1))]
  (println "deref: " (deref result))
  (println "@: " @result))


; Dereferencing a future will block if the futrue hasn't finished running
(let [result (future (Thread/sleep 3000)
                     (+ 1 1))]

  (println "This will print right away")
  (println "the result is: " @result)
  (println "It will be at least 3 seconds before I print"))


; Set a time limit on how long to wait for a future
(deref (future (Thread/sleep 10000) 0) 3000 5)


; Interrogate a future to see if it's done running with realized?
(realized? (future (Thread/sleep 1000)))

; Futures aren't "realized?" until they are dereferenced
(let [f (future)]
  @f
  (realized? f))

; When you dereference a future you indicate
; that the result is required RIGHT NOW!
; Evaluation will block until the result is obtained



; DELAYS: Define a task definition without having to execute it or
; require the result immediately

(def jackson-5-delay
  (delay (let [message "Just call my name and I'll be there"]
           (println "First deref:" message)
           message)))

; Nothing is printed yetl...

(force jackson-5-delay)

(deref jackson-5-delay)

; force has the same effect as deref but communicates more clearly that you're
; causing the task to start as opposed to waiting for it to finish

; Like futures, a DELAY is only run once and its result is cached

; USE CASE: Fire off a statement the first time one future out of a group
; of related futures finishes

; Protects against Mutual Exclusion Concurrency

(def gimli-headshots ["serious.jpg" "fun.jpg" "playful.jpg"])

(defn email-user
  [email-address]
  (println "Sending headshot notification to" email-address))

(defn upload-document
  "Needs to be implemented"
  [headshot]
  true)

(let [notify (delay (email-user "and-my-axe@gamil.com"))]
  (doseq [headshot gimli-headshots]
    (future (upload-document headshot)
            (force notify))))

; PROMISES: Allow you to express the expectation of a result independently of
; the task that should produce it and when the task should run

(def my-promise (promise))

(deliver my-promise (+ 1 2))

@my-promise

; USE CASE: Find the first satisfactory element in a collection of data.
(def yak-butter-international
  {:store "Yak Butter International"
   :price 90
   :smoothness 90})

(def butter-than-nothing
  {:store "Butter than Nothing"
   :price 150
   :smoothness 83})

;; This is the butter that meets our requirements
(def baby-got-yak
  {:store "Baby Got Yak"
   :price 94
   :smoothness 99})

(defn mock-api-call
  [result]
  (Thread/sleep 1000)
  result)

(defn satisfactory?
  "If the butter meets our criteria, return the butter, else return false"
  [butter]
  (and (<= (:price butter) 100)
       (>= (:smoothness butter) 97)
       butter))

(time (some (comp satisfactory? mock-api-call)
            [yak-butter-international butter-than-nothing baby-got-yak]))

; => "Elapsed time: 3002.132 msecs"
; => {:store "Baby Got Yak", :smoothness 99, :price 94}


; You can use a promise and futures to perform each check on a separate thread.

(time
 (let [butter-promise (promise)]
   (doseq [butter [yak-butter-international butter-than-nothing baby-got-yak]]
     (future (if-let [satisfactory-butter (satisfactory? (mock-api-call butter))]
               (deliver butter-promise satisfactory-butter))))
   (println "And the winner is:" @butter-promise)))

; => "Elapsed time: 1002.264 msecs"
; => And the winner is: {:store Baby Got Yak, :smoothness 99, :price 94}

; By decoupling the requirement for a result from
; how the result is actually computed, you can perform multiple computations
; in parallel and save yourself some time.

; You can view this as a way to protect yourself from the
; Reference Cell Concurrency Goblin. Since promises can only be written to once,
; you're can't create the kind of inconsistent state that arises from
; nondeterministic reads and writes.

; PROMISES can be used to register callbacks
(let [ferengi-wisdom-promise (promise)]
  ; Setup callback here.  It will block on its own thread
  ; until the promise if fulfilled
  (future (println "Her's some Ferengi wisdom:" @ferengi-wisdom-promise))
  (Thread/sleep 100)
  (deliver ferengi-wisdom-promise "Whisper your way to success."))


; Java Threading

; new and Classname. are two ways of instantiating an instance

(.start (new Thread (fn [] (println "hello"))))

(.start (Thread. (fn [] (println "foobar"))))

; proxy is used to  create a proxy object to
; implement a java interface for interop

; Useful for implementing Runnable
; http://docs.oracle.com/javase/tutorial/essential/concurrency/runthread.html

(.start (Thread. (proxy [Runnable] [] (run [] (println "I ran!")))))

(do (.start
     (new Thread (fn [] (Thread/sleep 2000)
                   (println "I print second"))))
    (.start
     (new Thread (fn [] (println "I print first")))))



; QUEUEING


(defn append-to-file
  [filename s]
  (spit filename s :append true))

(defn format-quote
  [quote]
  (str "=== BEGIN QUOTE===\n" quote "=== END QUOTE ===\n\n"))

(defn snag-quotes
  [n filename]
  (dotimes [_ n]
    (->> (slurp "http://www.iheartquotes.com/api/v1/random")
         format-quote
         (append-to-file filename)
         (future))))

(snag-quotes 2 "quotes.txt")

; Experementation
(let [result (future (Thread/sleep 2000)
                     (println "in another thread")
                     (Thread/sleep 2000)
                     (+ 1 1))]
  (println "no result yet...")
  (println (+ @result 2))
  (println @result))



