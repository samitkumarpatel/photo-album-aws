output "key_group_id" {
  description = "Key group to trust on the signed cache behavior."
  value       = aws_cloudfront_key_group.this.id
}

output "key_pair_id" {
  description = "Public key id; the application puts it in signed URLs as Key-Pair-Id."
  value       = aws_cloudfront_public_key.this.id
}

output "private_key_pem" {
  description = "PKCS#8 PEM private key the application signs with."
  value       = tls_private_key.this.private_key_pem_pkcs8
  sensitive   = true
}
