# linux-terminal — a Linux shell, in a VR headset, as text.
#
# Two halves that are built and installed separately: a Go server on the machine
# you want a shell on, and an Android client in the headset.

SERVER      := linux-terminal-server
PREFIX      ?= /usr/local
BINDIR      := $(PREFIX)/bin
UNITDIR     := $(HOME)/.config/systemd/user
APK         := client/app/build/outputs/apk/debug/app-debug.apk
PACKAGE     := dev.butschster.linuxterminal

.PHONY: all server client install install-server install-service uninstall run dev apk fmt test clean help

all: server client

help:
	@echo "make server           build the Go server into server/$(SERVER)"
	@echo "make install          install the server and its systemd user service"
	@echo "make run              build and run the server in this terminal"
	@echo "make client           build the Android client"
	@echo "make apk              build the client and install it on the attached headset"
	@echo "make dev              server in the foreground plus a fresh client on the headset"
	@echo "make uninstall        remove the server, the service and the app"

# ------------------------------------------------------------------- the server

server:
	cd server && go build -trimpath -ldflags "-s -w" -o $(SERVER) .

fmt:
	cd server && gofmt -w *.go && go vet ./...

test:
	cd server && go test ./...

run: server
	./server/$(SERVER) --cwd $(HOME)

install: install-server install-service
	@echo
	@echo "Installed. The headset should find this machine by itself;"
	@echo "if it does not, add $$(hostname -I | awk '{print $$1}'):9103 by hand."

install-server: server
	install -Dm755 server/$(SERVER) $(BINDIR)/$(SERVER)

# A user service, not a system one: the shells it opens are yours, they inherit
# your environment, and nothing here wants root.
install-service:
	@mkdir -p $(UNITDIR)
	@sed "s|@BINDIR@|$(BINDIR)|g; s|@HOME@|$(HOME)|g" \
	    packaging/linux-terminal-server.service > $(UNITDIR)/$(SERVER).service
	systemctl --user daemon-reload
	systemctl --user enable --now $(SERVER).service
	@systemctl --user --no-pager --lines=0 status $(SERVER).service || true

# ------------------------------------------------------------------- the client

client:
	cd client && ./gradlew :app:assembleDebug

apk: client
	adb install -r $(APK)

dev: apk
	adb shell am force-stop $(PACKAGE) || true
	adb shell am start -n $(PACKAGE)/.ServersActivity
	$(MAKE) run

# ------------------------------------------------------------------ removing it

uninstall:
	-systemctl --user disable --now $(SERVER).service
	-rm -f $(UNITDIR)/$(SERVER).service
	-systemctl --user daemon-reload
	-rm -f $(BINDIR)/$(SERVER)
	-adb uninstall $(PACKAGE)

clean:
	rm -f server/$(SERVER)
	cd client && ./gradlew clean
