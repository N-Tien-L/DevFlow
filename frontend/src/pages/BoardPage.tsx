export default function BoardPage() {
  return (
    <div className="p-6">
      <header className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900">DevFlow Board</h1>
        <p className="text-gray-500 mt-1">Trello for developers — real-time project tracking with Git integration</p>
      </header>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {['To Do', 'In Progress', 'Done'].map((col) => (
          <div key={col} className="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
            <h2 className="font-semibold text-gray-700 mb-3">{col}</h2>
            <div className="space-y-2">
              <div className="bg-gray-50 rounded p-3 border-l-4 border-blue-400">
                <p className="text-sm font-medium">Sample task card</p>
                <p className="text-xs text-gray-500 mt-1">TODO: real card CRUD (PRODUCT_SPEC 5.1)</p>
              </div>
            </div>
            <p className="text-xs text-gray-400 mt-3">TODO: drag-and-drop, real-time sync (PRODUCT_SPEC 5.1)</p>
          </div>
        ))}
      </div>
    </div>
  );
}
