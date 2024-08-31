(ns apossiblespace.test-factory)

(def ^:private counter (atom 0))

(defn- generate-unique-id []
  (swap! counter inc))

(defn create-test-user
  ([]
   (create-test-user {}))
  ([attrs]
   (let [id (generate-unique-id)]
     (merge
      {:email (str "test" id "@example.com")
       :username (str "username" id)
       :display_name (str "Test User " id)
       :password (str "password" id)
       :role "client"}
      attrs))))

(defn create-test-users [n]
  (repeatedly n create-test-user))
