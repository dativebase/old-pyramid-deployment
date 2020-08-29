FROM nginx:stable-alpine
RUN mkdir -p /etc/nginx/sites-enabled
RUN mkdir -p /var/www/html
EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]
