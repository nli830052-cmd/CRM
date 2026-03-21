from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime

class ContactBase(BaseModel):
    name: str
    phone_number: str
    organization: Optional[str] = None

class ContactCreate(ContactBase):
    pass

class Contact(ContactBase):
    id: str
    created_at: datetime
    last_contact: Optional[datetime] = None
    class Config:
        from_attributes = True

class CallBase(BaseModel):
    contact_id: str
    direction: Optional[str] = "OUT"
    duration: int
    timestamp: datetime

class CallCreate(CallBase):
    pass

class Call(CallBase):
    id: str
    class Config:
        from_attributes = True

class MessageBase(BaseModel):
    contact_id: str
    content: str
    direction: str
    timestamp: datetime

class MessageCreate(MessageBase):
    pass

class Message(MessageBase):
    id: str
    class Config:
        from_attributes = True

class AISummaryBase(BaseModel):
    contact_id: str
    summary: str
    status: str
    next_action: str

class AISummary(AISummaryBase):
    id: str
    updated_at: datetime
    class Config:
        from_attributes = True

class RecordingBase(BaseModel):
    contact_id: str
    file_path: str
    original_filename: Optional[str] = None
    summary: Optional[str] = None
    transcription: Optional[str] = None

class RecordingCreate(RecordingBase):
    pass

class Recording(RecordingBase):
    id: str
    timestamp: datetime
    class Config:
        from_attributes = True
