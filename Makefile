.PHONY: build-debug
build-debug:
	./gradlew :app:assembleDebug

.PHONY: export
export:
	@find . -type f \( -name "*.kt" -o -name "*.md" -o -name "*.xml" \) \
	    ! -path "*/.*" \
	    ! -path "*/test/*" \
	    ! -path "*/res/*" \
	    ! -path "*/build/*" \
	    ! -path "*/androidTest/*" \
	    ! -name "*Test.kt" \
	    -exec sh -c 'for file do \
	        echo "== $$file"; \
	        cat "$$file"; \
	    echo ""; \
	done' _ {} +

