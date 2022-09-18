; HDFS
;
; hdfs://host/path/to/file.txt
; hdfs:///path/to/file.txt
;
;  :principal       (optional)
;  :keytab          (optional)  <-- path to a file
;  :access          (optional)
;  :secret          (optional)
;
;  NOTE: to use `kinit` isntead of keytab file, pass empty creds (`{}` or all nil values)
;
(ns uio.fs.hdfs
  (:require [clojure.string :as str]
            [uio.impl :refer :all]
            [me.raynes.fs :as fs])
  (:import [java.io IOException]
           [java.net URL]
           [java.util Iterator Date]
           [org.apache.hadoop.conf Configuration]
           [org.apache.hadoop.fs FileAlreadyExistsException FileStatus FileSystem Path RemoteIterator]
           [org.apache.hadoop.security UserGroupInformation]))

(deftype HdfsIterator [^FileSystem fs ^RemoteIterator ri] Iterator
  (hasNext  [_] (.hasNext ri))
  (next     [_] (.next ri)))

(defn ->config [^String url]
  (let [c           (Configuration.)
        creds       (url->creds url)

        principal   (:principal creds)
        keytab-path (some-> (:keytab creds) path)
        aws-access  (:access creds)
        aws-secret  (:secret creds)]

    (when (and aws-access aws-secret)
      (.set c "fs.s3a.impl"               "org.apache.hadoop.fs.s3a.S3AFileSystem")
      (.set c "fs.s3a.access.key"         aws-access)
      (.set c "fs.s3a.secret.key"         aws-secret)

      (.set c "fs.s3n.impl"               "org.apache.hadoop.fs.s3native.NativeS3FileSystem")
      (.set c "fs.s3n.awsAccessKeyId"     aws-access)
      (.set c "fs.s3n.awsSecretAccessKey" aws-secret)

      (.set c "fs.s3.impl"                "org.apache.hadoop.fs.s3.S3FileSystem")
      (.set c "fs.s3.awsAccessKeyId"      aws-access)
      (.set c "fs.s3.awsSecretAccessKey"  aws-secret))

    (doseq [url ["file:///etc/hadoop/conf/core-site.xml"
                 "file:///etc/hadoop/conf/hdfs-site.xml"]]
      (when (exists? url)
        (.addResource c (URL. url))))

    (when-let [hcd (System/getenv "HADOOP_CONF_DIR")]
      (doseq [url (map #(str/join ["file://" (fs/absolute hcd) "/" %])
                       ["core-site.xml" "hdfs-site.xml"])]
        (when (exists? url)
          (.addResource c (URL. url))
          )))

    (.set c "hadoop.security.authentication" "kerberos")

    (UserGroupInformation/setConfiguration c)

    ; only use keytab creds if either user or keytab path was specified, otherwise rely on default auth (e.g. if ran from kinit/Yarn)
    (when (or principal keytab-path)
      (UserGroupInformation/loginUserFromKeytab principal keytab-path)

      ; TODO is there a way to provide more information about the failure?
      (if-not (UserGroupInformation/isLoginKeytabBased)
        (die "Could not authenticate. Wrong or missing keytab?")))

    c))

(defn with-hdfs [^String url fs->x]
  (with-open [fs (FileSystem/newInstance (->config url))]
    (fs->x fs)))

(defmethod from    :hdfs [url & args] (wrap-is #(FileSystem/newInstance (->config url))
                                               #(.open % (Path. (->URI url)))
                                               #(.close %)))

(defmethod to      :hdfs [url & args] (wrap-os #(FileSystem/newInstance (->config url))
                                               #(.create % (Path. (->URI url)))
                                               #(.close %)))

(defmethod exists? :hdfs [url & args] (with-hdfs url #(.exists % (Path. (->URI url)))))
(defmethod size    :hdfs [url & args] (with-hdfs url #(.getLen (.getFileStatus % (Path. (->URI url))))))
(defmethod delete  :hdfs [url & args] (with-hdfs url #(do (and (not (.delete % (Path. (->URI url)) false))
                                                               (.exists % (Path. (->URI url)))
                                                               (die (str "Could not delete: got `false` and the file still exists: " url) ))
                                                          nil)))

(defmethod mkdir   :hdfs [url & args] (with-hdfs url #(do (or (try (.mkdirs % (Path. (->URI url)))
                                                                   (catch FileAlreadyExistsException _
                                                                     (die (str "A file with this name already exists: " url))))
                                                              (try (.isDirectory (.getFileStatus % (Path. (->URI url))))
                                                                   (catch IOException e
                                                                     (die (str "Could not make directory: " url) e)))
                                                              (die (str "A file with this name already exists: " url)))
                                                          nil)))

(defn f->kv [attrs? ^FileStatus f]
  (merge {:url (str (.toUri (.getPath f))
                    (if (.isDirectory f)
                      default-delimiter))}

         (if (.isFile f)      {:size (.getLen f)})
         (if (.isDirectory f) {:dir  true})

         (if attrs?
           (merge {:accessed (-> f .getAccessTime Date.)
                   :modified (-> f .getModificationTime Date.)
                   :owner    (-> f .getOwner)
                   :group    (-> f .getGroup)
                   :perms    (str (.getPermission f))}

                  (if (.isSymlink f)   {:symlink     (-> f .getSymlink .toUri str)})
                  (if (.isEncrypted f) {:encrypted   true})
                  (if (.isFile f)      {:replication (-> f .getReplication)
                                        :block-size  (-> f .getBlockSize)})))))

(defmethod ls      :hdfs [url & args] (let [opts (get-opts default-opts-ls url args)
                                            fs   (FileSystem/newInstance (->config url))
                                            p    (Path. (->URI url))]
                                        (cond->> (->> (if (or (str/includes? url "?")
                                                              (str/includes? url "*"))
                                                        (.globStatus fs p)
                                                        (->> (if (:recurse opts) ; RemoteIterator<LocatedFileStatus>
                                                               (.listFiles fs p true)
                                                               (.listStatusIterator fs p))
                                                             (HdfsIterator. fs) ; Iterator<LocatedFileStatus>
                                                             iterator-seq)) ; [FileStatus]
                                                      (map (partial f->kv (:attrs opts))) ; [{kv}]
                                                      (close-when-realized-or-finalized #(.close fs)))

                                                 (:recurse opts)
                                                 (intercalate-with-dirs))))

