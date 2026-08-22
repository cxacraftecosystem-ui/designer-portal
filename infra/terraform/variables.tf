variable "aws_region" {
  description = "AWS region for S3 + EC2 (keep S3 and the API in the same region)."
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  description = "Name prefix for created resources."
  type        = string
  # DELIBERATELY NOT REBRANDED TO "design-workshop". This default is not a label — it is the primary
  # identifier of resources that already exist in the account, and `terraform.tfstate` in this
  # directory records them: the IAM user `fieldrepo-media`, its inline policy `fieldrepo-media-s3`,
  # and the security group `fieldrepo-api`. An IAM user's name cannot be changed in place, so the
  # next `terraform apply` after an edit here is a DESTROY-AND-CREATE: the old user goes away, and
  # with it `aws_iam_access_key.media` — the exact AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY pair
  # sitting in the running API's `/home/ubuntu/app/backend/.env`. The live box keeps serving with
  # credentials that no longer resolve, so every presign and every media upload starts returning
  # InvalidAccessKeyId while /health stays green, and nothing in the API's own logs points at
  # Terraform as the cause. The security group is replaced too, which detaches and reattaches the
  # instance's only ingress path.
  #
  # Note this prefix does NOT name the media bucket: `bucket_name` is a separate, required variable
  # — this portal's designrepo workspace passes `designrepo-media-626159998512`, and the sibling
  # field repository has its own `fieldrepo-media-…`; the comment named the SIBLING's bucket until
  # 2026-08-22 — because an S3 bucket name is globally unique and
  # replacing one would strand every uploaded photograph rather than move it.
  #
  # Renaming this is a migration, not an edit: create the new IAM user, roll the new key into the
  # box's .env, confirm uploads, then remove the old one.
  default     = "fieldrepo"
}

variable "bucket_name" {
  description = "Globally-unique S3 bucket name for media."
  type        = string
}

variable "ssh_key_name" {
  description = "Name of an existing EC2 key pair for SSH into the API box."
  type        = string
}

variable "ssh_ingress_cidr" {
  description = "CIDR allowed to SSH (use YOUR.IP/32, not 0.0.0.0/0)."
  type        = string
}

variable "cors_allowed_origins" {
  description = "Origins allowed to PUT/GET media from the browser (the web frontend URL)."
  type        = list(string)
  default     = ["*"]
}
