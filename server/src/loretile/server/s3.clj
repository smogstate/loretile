(ns loretile.server.s3
  "One S3 client for the config file and the merge files, built from
   [:aws :region] / [:aws :endpoint] with the SDK's default credential
   chain (IAM role, AWS_ACCESS_KEY_ID…). An endpoint means an
   S3-compatible store (Yandex, MinIO): path-style addressing."
  (:require [clojure.string :as str])
  (:import (java.net URI)
           (software.amazon.awssdk.core ResponseBytes)
           (software.amazon.awssdk.core.sync ResponseTransformer)
           (software.amazon.awssdk.regions Region)
           (software.amazon.awssdk.services.s3 S3Client)
           (software.amazon.awssdk.services.s3.model GetObjectRequest GetObjectResponse ListObjectsV2Request S3Exception S3Object)))

(defn parse-url
  "s3://bucket/some/key → {:bucket \"bucket\" :key \"some/key\"}"
  [u]
  (let [[_ bucket key] (re-matches #"s3://([^/]+)/?(.*)" u)]
    (when-not bucket (throw (ex-info (str "Not an s3:// url: " u) {})))
    {:bucket bucket :key key}))

(defonce ^:private clients (atom {}))

(defn- client ^S3Client [{:keys [region endpoint] :as aws}]
  (or (get @clients aws)
      (let [b (S3Client/builder)]
        (when region (.region b (Region/of region)))
        (when endpoint
          (.endpointOverride b (URI. endpoint))
          (.forcePathStyle b true))
        (let [c (.build b)]
          (swap! clients assoc aws c)
          c))))

(defn get-object
  "{:bytes … :etag …}, or :not-modified when the object still has `etag`."
  [aws bucket key etag]
  (let [req (cond-> (-> (GetObjectRequest/builder) (.bucket bucket) (.key key))
              etag (.ifNoneMatch (str etag)))]
    (try
      (let [^ResponseBytes r (.getObject (client aws) ^GetObjectRequest (.build req) (ResponseTransformer/toBytes))]
        {:bytes (.asByteArray r) :etag (.eTag ^GetObjectResponse (.response r))})
      (catch S3Exception e
        (if (= 304 (.statusCode e)) :not-modified (throw e))))))

(defn list-keys [aws bucket prefix]
  (let [req (-> (ListObjectsV2Request/builder) (.bucket bucket) (.prefix prefix) .build)]
    (->> (.contents (.listObjectsV2Paginator (client aws) ^ListObjectsV2Request req))
         (map #(.key ^S3Object %))
         vec)))

(defn join-key [prefix k]
  (if (str/blank? prefix) k (str (str/replace prefix #"/+$" "") "/" k)))
