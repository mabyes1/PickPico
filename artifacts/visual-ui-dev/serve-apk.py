from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil

apk = Path(__file__).with_name('pickpico-0.16.77.apk')

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/pickpico-0.16.77.apk':
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/vnd.android.package-archive')
        self.send_header('Content-Length', str(apk.stat().st_size))
        self.end_headers()
        with apk.open('rb') as source:
            shutil.copyfileobj(source, self.wfile)

print('Serving only test APK on 192.168.0.20:41288', flush=True)
ThreadingHTTPServer(('192.168.0.20', 41288), Handler).serve_forever()
