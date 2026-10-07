# Key pair for CloudFront signed URLs: CloudFront trusts the public key through a key group, and the
# application signs URLs with the private key. The private key lives in Terraform state, so keep
# state encrypted and private (the S3 backend in environments/* does). Rotate by adding a second
# key to the group before removing the first.
resource "tls_private_key" "this" {
  algorithm = "RSA"
  rsa_bits  = 2048 # CloudFront accepts RSA 2048 and ECDSA P-256; RSA keeps the PEM small enough for Lambda env vars
}

resource "aws_cloudfront_public_key" "this" {
  name        = var.name
  comment     = "Signs ${var.name} media URLs"
  encoded_key = tls_private_key.this.public_key_pem
}

resource "aws_cloudfront_key_group" "this" {
  name    = var.name
  comment = "Trusted signers for ${var.name} media URLs"
  items   = [aws_cloudfront_public_key.this.id]
}
