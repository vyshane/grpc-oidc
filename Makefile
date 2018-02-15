# Default to version specified in build.sbt
ifeq ($(VERSION),)
  version_override =
else
  version_override = VERSION=$(VERSION)
endif

test:
	sbt test

publish:
	$(version_override) sbt publish
