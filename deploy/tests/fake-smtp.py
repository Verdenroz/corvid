#!/usr/bin/env python3
"""A fake inbound SMTP server for update.test.sh. Never the real one.

Prints its port on stdout, then serves until killed. Appends every command line it receives to
<dir>/commands and every accepted message to <dir>/messages. Answers 500 to a command it does not
know, which the real server would punish with a ban.
"""
import socketserver, sys

out = sys.argv[1]


class H(socketserver.StreamRequestHandler):
    def say(self, text):
        self.wfile.write(text.encode() + b"\r\n")

    def handle(self):
        self.say("220 fake ready")
        data = False
        message = []
        for raw in self.rfile:
            line = raw.decode().rstrip("\r\n")
            if data:
                if line == ".":
                    data = False
                    open(f"{out}/messages", "a").write("\n".join(message) + "\n====\n")
                    self.say("250 queued")
                else:
                    message.append(line)
                continue
            open(f"{out}/commands", "a").write(line + "\n")
            verb = line.split(" ")[0].upper()
            if verb == "EHLO":
                self.say("250-fake")
                self.say("250 8BITMIME")
            elif verb in ("MAIL", "RCPT"):
                self.say("250 ok")
            elif verb == "DATA":
                data = True
                self.say("354 go")
            elif verb == "QUIT":
                self.say("221 bye")
                return
            else:
                self.say("500 unknown")


class S(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


with S(("127.0.0.1", 0), H) as server:
    print(server.server_address[1], flush=True)
    server.serve_forever()
