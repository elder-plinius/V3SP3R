import XCTest

final class VesperUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    @MainActor
    func testOnboardingRequiresAuthorization() {
        let app = XCUIApplication()
        app.launchArguments = ["-ui-testing"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Meet Vesper"].waitForExistence(timeout: 3))
        app.buttons["Continue"].tap()
        XCTAssertTrue(app.buttons["Set up later"].waitForExistence(timeout: 2))
        app.buttons["Set up later"].tap()
        XCTAssertTrue(app.buttons["Enter Vesper"].waitForExistence(timeout: 2))
        let enterButton = app.buttons["enterVesperButton"]
        XCTAssertFalse(enterButton.isEnabled)
        app.switches["authorizedUseToggle"].tap()
        enterButton.tap()
        XCTAssertTrue(app.navigationBars["Vesper"].waitForExistence(timeout: 3))
    }
}
