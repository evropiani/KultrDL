"""A throwaway FTP server for the smoke test: user kultr, password kultr-pass, files in /tmp/ftp."""
import os

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

root = "/tmp/ftp"
os.makedirs(root, exist_ok=True)
authorizer = DummyAuthorizer()
authorizer.add_user("kultr", "kultr-pass", root, perm="elradfmwMT")
handler = FTPHandler
handler.authorizer = authorizer
handler.passive_ports = range(30000, 30050)
FTPServer(("0.0.0.0", 2121), handler).serve_forever()
