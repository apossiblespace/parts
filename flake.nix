{
  description = "Parts project development environment";

  inputs = {
    # Use nixpkgs-unstable for latest Clojure 1.12.4
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # Java 21 Temurin
        jdk = pkgs.temurin-bin-21;

        # PDF document fonts (see ADR-0008): the render pipeline reads
        # these exact files via PARTS__RENDER__FONT_DIR. Pinned so dev,
        # CI, and (manually installed, see runbook) production hosts
        # all measure and embed identical bytes.
        notoSansCjkTc =
          let
            fetchFont = file: hash: pkgs.fetchurl {
              url = "https://raw.githubusercontent.com/notofonts/noto-cjk/Sans2.004/Sans/OTF/TraditionalChinese/${file}";
              inherit hash;
            };
            regular = fetchFont "NotoSansCJKtc-Regular.otf"
              "sha256-3OCL1P2Rqoqnbtj+pLaUwt+4VQ9nhx4yaEMhLdvriLQ=";
            bold = fetchFont "NotoSansCJKtc-Bold.otf"
              "sha256-PuFg5QFRBuPsGjlDAd9U+pu7+KJRUZmErsXAq8UIQMA=";
          in
          pkgs.runCommand "noto-sans-cjk-tc-2.004" { } ''
            mkdir -p $out
            cp ${regular} $out/NotoSansCJKtc-Regular.otf
            cp ${bold} $out/NotoSansCJKtc-Bold.otf
          '';
      in {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            # Java
            jdk

            # Clojure
            clojure
            clj-kondo
            cljfmt

            # JavaScript tools
            nodejs_20
            pnpm

            # Database
            postgresql_16

            # Build tools
            gnumake

            # Development tools
            git
            ripgrep
            fd
            uv # runs postgres-mcp (see .mcp.json)

            # Optional but useful
            jq
            httpie
            watchexec
            rclone
          ];
          
          shellHook = ''
            export JAVA_HOME=${jdk}

            # PDF document fonts (Noto Sans CJK TC) for the render pipeline
            export PARTS__RENDER__FONT_DIR=${notoSansCjkTc}

            # Set up local paths for npm packages
            export PATH="$PWD/node_modules/.bin:$PATH"

            # PostgreSQL setup
            export PGDATA="$PWD/.postgres"
            export PGHOST=localhost
            export PGPORT=5432
            export PGDATABASE=parts_dev
            export PGUSER=$USER

            # Initialize PostgreSQL if needed
            if [ ! -d "$PGDATA" ]; then
              echo "📦 Initializing PostgreSQL database..."
              initdb --auth=trust --no-locale --encoding=UTF8
            fi

            echo "🌀 Parts Development Environment"
            echo ""
            echo "PostgreSQL commands:"
            echo "  make pg-start        - Start PostgreSQL and create databases"
            echo "  make pg-stop         - Stop PostgreSQL"
            echo "  make pg-status       - Check PostgreSQL status"
            echo "  make pg-console      - Open psql console (dev DB)"
            echo "  make pg-console-test - Open psql console (test DB)"
            echo ""
            echo "Available commands:"
            echo "  make help     - Show all available make targets"
            echo "  make repl     - Start development REPL"
            echo "  make test     - Run tests"
            echo "  nix flake update - Update all dependencies"
            echo ""
            echo "Java:       $(java -version 2>&1 | head -n 1)"
            echo "Clojure:    $(clojure --version)"
            echo "Node:       $(node --version)"
            echo "pnpm:       $(pnpm --version)"
            echo "PostgreSQL: $(postgres --version)"
          '';
        };
      });
}
