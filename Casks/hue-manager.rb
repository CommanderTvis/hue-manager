cask "hue-manager" do
  version "2026.03.23-ad4d445"
  sha256 "3b807076e0c4df07d7a08e89999e24153da8b1efde9095d1ede9e25febcbfaf2"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/379675363",
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
