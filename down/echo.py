#!/usr/bin/env python3
"""极简 echo 服务，多线程，返回固定 200 + 小 JSON。零依赖。"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import json

class EchoHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({"status": 200, "message": "ok"}).encode())

    do_POST = do_GET

    def log_message(self, format, *args):
        pass

ThreadingHTTPServer(('0.0.0.0', 8081), EchoHandler).serve_forever()
print('echo 服务已启动: http://0.0.0.0:8081')
