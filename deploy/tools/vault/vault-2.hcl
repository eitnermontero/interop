ui = true
disable_mlock = true

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

storage "raft" {
  path    = "/vault/data"
  node_id = "mwc-vault-2"

  retry_join {
    leader_api_addr = "http://mwc-vault-1:8200"
  }
  retry_join {
    leader_api_addr = "http://mwc-vault-3:8200"
  }
}
