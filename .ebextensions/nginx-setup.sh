#!/bin/bash

cat >/etc/nginx/conf.d/https_custom.conf <<EOL
server {
    listen 443;
    server_name localhost;

    ssl on;

    ssl_certificate      /etc/letsencrypt/live/ebcert/fullchain.pem;
    ssl_certificate_key  /etc/letsencrypt/live/ebcert/privkey.pem;

    ssl_session_timeout 5m;

    ssl_protocols TLSv1 TLSv1.1 TLSv1.2;
    ssl_ciphers "EECDH+AESGCM:EDH+AESGCM:AES256+EECDH:AES256+EDH";
    ssl_prefer_server_ciphers on;

    location / {
      root /var/app/current/static;
      try_files \$uri \$uri.html /index.html;
    }

    location /api/ {
      proxy_pass http://docker;
      proxy_http_version 1.1;

      proxy_set_header Connection "";
      proxy_set_header Host \$host;
      proxy_set_header X-Real-IP \$remote_addr;
      proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }
}
EOL

cat >/etc/nginx/conf.d/http_custom_proxy.conf <<EOL
  server {
    listen 80;
    return 301 https://\$host\$request_uri;
  }
EOL

service nginx restart