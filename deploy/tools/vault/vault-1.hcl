ui = true
disable_mlock = true

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

storage "raft" {
  path    = "/vault/data"
  node_id = "mwc-vault-1"

  retry_join {
    leader_api_addr = "http://mwc-vault-2:8200"
  }
  retry_join {
    leader_api_addr = "http://mwc-vault-3:8200"
  }
}

# api_addr y cluster_addr se inyectan por env (VAULT_API_ADDR / VAULT_CLUSTER_ADDR)
# desde el compose, apuntando a MWC_HOST_IP para alcance externo.
