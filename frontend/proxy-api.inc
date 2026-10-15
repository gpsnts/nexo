# O nginx resolve nomes só ao iniciar; com variável + resolver do Docker ele consulta de novo (valid=5s). Sem isso, recriar o backend
# (novo IP) deixa o /api em 502 até reiniciar o frontend.
resolver 127.0.0.11 valid=5s ipv6=off;
set $backend http://backend:8080;
rewrite ^/api/(.*)$ /$1 break;
proxy_pass $backend;
# O backend limita por IP; precisa do IP real, que ele lê de X-Forwarded-For (server.forward-headers-strategy=native).
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
