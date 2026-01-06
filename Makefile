.PHONY: test version-sync jar deploy clean

VERSION := $(shell cat version.txt)
CLOJARS_USERNAME := $(shell python3 -c "import os,xml.etree.ElementTree as ET,pathlib; u=os.getenv('CLOJARS_USERNAME'); p=pathlib.Path('~/.m2/settings.xml').expanduser(); \
print(u or (ET.parse(p).getroot().find('.//server[id=\"clojars\"]').findtext('username') if p.exists() else ''))")
CLOJARS_PASSWORD := $(shell python3 -c "import os,xml.etree.ElementTree as ET,pathlib; pw=os.getenv('CLOJARS_PASSWORD'); p=pathlib.Path('~/.m2/settings.xml').expanduser(); \
print(pw or (ET.parse(p).getroot().find('.//server[id=\"clojars\"]').findtext('password') if p.exists() else ''))")

test:
	clojure -M:test

version-sync:
	python3 -c "import pathlib,re; v=pathlib.Path('version.txt').read_text().strip(); p=pathlib.Path('pom.xml'); t=p.read_text(); t=re.sub(r'<version>[^<]+</version>', f'<version>{v}</version>', t, count=1); t=re.sub(r'<tag>[^<]+</tag>', f'<tag>v{v}</tag>', t, count=1); p.write_text(t)"

jar: version-sync
	clojure -T:build jar

deploy: jar
	CLOJARS_USERNAME=$(CLOJARS_USERNAME) CLOJARS_PASSWORD=$(CLOJARS_PASSWORD) clojure -T:deploy deploy :installer :remote :artifact "\"target/physics-$(VERSION).jar\"" :pom-file "\"pom.xml\"" :sign-releases? false

clean:
	rm -rf target
