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
; Sometimes the best way to handle concurrent tasks is to re-serialize them.
; You can do that by placing your tasks onto a queue. In this example, you'll
; make API calls to pull random quotes from I Heart Quotes and write them to
; your own quote library. For this process, you want to allow the API calls
; to happen concurrently but you want to serialize the writes so that none of
; the quotes get garbled

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
         ; Because te file is shared across threads
         ; there is the possibility of writes getting mixed up
         (append-to-file filename)
         (future))))

(defn random-quote
  []
  (comment (format-quote (slurp "http://www.iheartquotes.com/api/v1/random")))
  "foo bar")


(defmacro enqueue
  [q concurrent-promise-name & work]
  ; Stuff that should be done concurrently (querying api for quote)
  ; Stuff that should be serialized (writing quotes to file)
  (let [concurrent (butlast work)
        serialized (last work)]
    ; Create a promise that will be fulfilled when concurrent
    ; work has been completed
    `(let [~concurrent-promise-name (promise)]
       (future (deliver ~concurrent-promise-name (do ~@concurrent)))
       ; Previous future / promise  in queue dereferenced here to block
       ; Until it has completed its task
       (deref ~q)
       ; Writing to the file is dereferencing the concurrent promise
       ; So the writing will block until that future has ben realized
       ~serialized
       ; Return the promise name so the next item in the queue
       ; Can determine if we are finished doing our work
       ~concurrent-promise-name)))


(defmacro snag-quotes-queued
  [n filename]
  (let [quote-gensym (gensym)
        queue `(enqueue ~quote-gensym
                        (random-quote)
                        (append-to-file ~filename @~quote-gensym))]
    `(-> (future)
         ~@(take n (repeat queue)))))

(snag-quotes-queued 4 "quotes.txt")

; => expands to:
(-> (future)
    (enqueue G__627 (random-quote) (append-to-file "quotes.txt" @G__627))
    (enqueue G__627 (random-quote) (append-to-file "quotes.txt" @G__627))
    (enqueue G__627 (random-quote) (append-to-file "quotes.txt" @G__627))
    (enqueue G__627 (random-quote) (append-to-file "quotes.txt" @G__627)))


(defmacro wait
  "Sleep `timeout` seconds before evaluating body"
  [timeout & body]
  `(do (Thread/sleep ~timeout) ~@body))

(snag-quotes-queued 4 "quotes.txt")

; => expands to:
(-> (future)
    (enqueue G__627 (random-quote) (append-to-file "quotes.txt" @G__627))
    (enqueue G__627 (random-quote) (append-to-file "quotes.txt" @G__627))
    (enqueue G__627 (random-quote) (append-to-file "quotes.txt" @G__627))
    (enqueue G__627 (random-quote) (append-to-file "quotes.txt" @G__627)))


(defmacro wait
  "Sleep `timeout` seconds before evaluating body"
  [timeout & body]
  `(do (Thread/sleep ~timeout) ~@body))

(println (future (wait 200 (println "'Ello, gov'na!"))))

(println "hello")

(do (future (wait 200 (println "'Ello, gov'na!")))
    (future (wait 400 (println "Pip pip!")))
    (future (wait 100 (println "Cheerio!"))))

(time @(-> (future (wait 200 (println "'Ello, gov'na!")))
           (enqueue saying (wait 400 "Pip pip!") (println @saying))
           (enqueue saying (wait 100 "Cheerio!") (println @saying))))

; Reference Type Properties
;
; Synchronous: Will block while updating
;
; Asynchronous: Updated in backround.  No blocking.
;
; Coordinated: Uses STM to create a transaction
; that ensures multiple instances are updated in lock-step
;
; Un-Coordinated: Does not ensure any other
; instances are updated in lock-step


; REF
; Coordinated Synchronous Updates

; ATOM
; Un-Coordinated Synchronous Updates

; AGENT
; Un-Coordinated Asynchronous Updates

; VAR

(def my-atom (atom :foo))


(let [p1 (promise)]
  (println "spawning threads...")
  (println @my-atom)
  (future
    (Thread/sleep 3000)
    (reset! my-atom :bar)
    (deliver p1 @my-atom))
  (println "blocking until promise fulfilled")
  @p1
  (println "promise fulfilled")
  (println "After first update")
  (println @my-atom)
  (future
    (Thread/sleep 3000)
    (reset! my-atom :wizzle)
    (println "val after second update")
    (println @my-atom))
  (println "second update requested but val is...")
  (println @my-atom))


(-> 1
    (partial + 1)
    println)

; Experementation
(let [result (future (Thread/sleep 2000)
                     (println "in another thread")
                     (Thread/sleep 2000)
                     (+ 1 1))]
  (println "no result yet...")
  (println (+ @result 2))
  (println @result))

; Create a file with 35000 records
; Each record with an incrementing id
; And a random value between 1 35000
;
; ex.
; 1 5
; 2 15
; 3 2
; 4 19
; etc.

(def data-seq
  "Creates colums of data with an auto incrementing key
   and a random dumber"
  (partition 4 (interleave (iterate inc 1)
                           (repeatedly #(identity \space))
                           (repeatedly #(rand-int 20))
                           (repeatedly #(identity \newline)))))


(defn create-data-rows! [count]
  (let [data (take count data-seq)]
    (spit "sample-data.txt" (apply str (map #(apply str %) data)))))


; Time the execution of the following two trials:

; Read and Parse the data
(def data (read-and-parse (slurp "sample-data.txt")))


(defn read-and-parse [data]
  (for [col (map #(clojure.string/split % #" ")
                 (clojure.string/split data #"\n"))]
    (map #(Integer. %) col)))



; Search the file on one core for the top 5 largest record(s)
(take 3 (sort-by last > data))

; Search the file on multiple cores for the top 5 largest record(s)



