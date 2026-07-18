cask "hue-manager" do
  version "2026.07.18-2685e1d"
  sha256 "26d9d28b36ff29e903840d1e238a54e85ef692ccfbfd56235a9ba7fd77d09d14"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/481358439",
      header: [
        "Authorization: token #{ENV.fetch("HOMEBREW_GITHUB_API_TOKEN", "")}",
        "Accept: application/octet-stream",
      ]
  name "Hue Manager"
  desc "Philips Hue lamp management desktop application"
  homepage "https://github.com/CommanderTvis/hue-manager"

  depends_on macos: ">= :ventura"

  app "Hue Manager.app"

  zap trash: [
    "~/Library/Preferences/io.github.commandertvis.huemanager.plist",
    "~/Library/Application Support/io.github.commandertvis.huemanager",
  ]
end
